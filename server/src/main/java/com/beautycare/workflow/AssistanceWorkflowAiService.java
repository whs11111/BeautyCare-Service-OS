package com.beautycare.workflow;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.beautycare.ai.ModelClient;
import com.beautycare.ai.ModelClient.ModelUsage;
import com.beautycare.ai.ModelRouter;
import com.beautycare.ai.TokenMeter;
import com.beautycare.api.AgentController.AssistanceContextRequest;
import com.beautycare.api.AgentController.ContextWorkflowResponse;
import com.beautycare.api.AgentController.EmotionTrendPoint;
import com.beautycare.api.AgentController.ReplyDraftResponse;
import com.beautycare.api.AgentController.ResolvedEvent;
import com.beautycare.api.AgentController.RiskWorkflowResponse;
import com.beautycare.api.AgentController.TimelineNode;
import com.beautycare.api.AgentController.UnresolvedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AssistanceWorkflowAiService {

    private final ModelClient modelClient;
    private final ContextCompressor compressor;
    private final TokenMeter tokenMeter;
    private final ObjectMapper objectMapper;
    private final int lightModelMaxMessages;
    /** 会话业务事实指纹 → 上次前情分析结果；事实未变化时直接复用，不再调用模型。 */
    private final Map<Long, FingerprintedContext> contextByConversation = new ConcurrentHashMap<>();

    public AssistanceWorkflowAiService(
            ModelClient modelClient,
            ContextCompressor compressor,
            TokenMeter tokenMeter,
            ObjectMapper objectMapper,
            @Value("${ai.routing.light-model-max-messages:6}") int lightModelMaxMessages) {
        this.modelClient = modelClient;
        this.compressor = compressor;
        this.tokenMeter = tokenMeter;
        this.objectMapper = objectMapper;
        this.lightModelMaxMessages = lightModelMaxMessages;
    }

    public ContextWorkflowResponse analyzeContext(AssistanceContextRequest request) {
        return analyzeContext(request, null);
    }

    public ContextWorkflowResponse analyzeContext(
            AssistanceContextRequest originalRequest,
            Consumer<ModelUsage> usageSink) {
        return analyzeContext(originalRequest, usageSink, ContextCompressor.Profile.CONTEXT);
    }

    public ContextWorkflowResponse analyzeResolvedEvents(AssistanceContextRequest request) {
        return analyzeContext(request, null, ContextCompressor.Profile.RESOLVED_EVENTS);
    }

    private ContextWorkflowResponse analyzeContext(
            AssistanceContextRequest originalRequest,
            Consumer<ModelUsage> usageSink,
            ContextCompressor.Profile profile) {
        ContextCompressor.Result compressed = compressor.compress(originalRequest, profile);
        AssistanceContextRequest request = compressed.request();

        String fingerprint = factsFingerprint(request);
        FingerprintedContext previous = request.conversationId() == null
                ? null : contextByConversation.get(request.conversationId());
        if (previous != null && previous.fingerprint().equals(fingerprint)) {
            tokenMeter.recordSkip("context", TokenMeter.estimateTokens(fingerprint));
            if (usageSink != null) usageSink.accept(new ModelUsage("context", "unchanged", 0, 0, 0, true));
            return previous.response();
        }
        tokenMeter.recordTrim("context", (long) Math.ceil(compressed.savedChars() / 1.5));

        // 模型路由：短会话且无风险信号时使用轻量模型。
        int size = request.messages() == null ? 0 : request.messages().size();
        boolean riskSignal = RuleSignals.needsSemanticModel(userTexts(request));
        String routedTier = size <= lightModelMaxMessages && !riskSignal ? ModelRouter.LIGHT : ModelRouter.STANDARD;

        JsonNode result = modelClient.generateJson("context", """
                你是美妆电商客服辅助系统的前情分析节点。
                根据输入的聊天、订单、工单和承诺信息完成：
                客服领取会话、切换客服、接待身份介绍和自动问候是平台通知，不是用户诉求或业务进展，不得写入事件总结、处理状态或timeline。previousSummary若含此类事项应删除。
                仅总结输入中最近的聊天，重点围绕当前正在处理的诉求；已经结束且与当前诉求无关的早期事件不得写入摘要、状态或轨迹。
                1. 用不超过100个汉字总结前情；
                2. 提取当前用户诉求；
                3. 提取有明确证据的用户标签。
                4. resolvedEvents记录当前输入日期内全部已经闭环的用户要求，不限制数量，不得遗漏或只选择最后发生的事项。每项必须同时说明用户提出了什么要求、客服如何满足或完成该要求，以及完成时间。
                previousSummary中可能包含上一次针对同一日期生成的resolvedEvents。必须将其作为稳定基线逐项核对：聊天事实没有变化时保留原有事件、措辞和完成时间；只在出现新的完成事项、明确状态变化、重复事件需要合并或旧结论与聊天证据冲突时修改。
                不得仅为了换一种说法而重写已有结论，也不得因为本次重新生成而删除聊天中仍有证据支持的已完成事件。
                userRequest使用“用户提出/用户要求/用户询问……”概括用户要求；serviceResolution使用“已……”概括客服实际满足要求或明确答复的结果，两者组合后必须是一句自然、简洁的事件结论。
                例如用户要求退款且客服已赔偿100元，应返回userRequest“用户提出退款”、serviceResolution“已给予100元赔偿”。completedAt优先原样使用完成该事项的客服消息sentAt，没有sentAt时使用原始time，不得编造。
                只有聊天中同时存在用户要求和客服完成结果时才能列入resolvedEvents；尚未答复、仍在查询或仅表示“稍后处理”的事项必须放入unresolvedEvents。
                5. unresolvedEvents用最多3条短句概括客服仍需处理、响应或闭环的事项，全部采用客服待办视角，但省略“客服”主语。
                每项必须返回description和sourceMessageId。sourceMessageId必须原样复制触发该待办的用户消息id；只能关联role为user的消息，不得填写客服消息id或编造id。
                使用“尚未向用户反馈……”“尚未响应用户的……”“等待用户提供……”等动作表达。
                禁止写成“用户尚未收到……”“客服尚未……”或“……未向用户展示”。
                例如“用户尚未收到订单状态反馈”应写成“尚未向用户反馈订单状态”；“客服尚未响应订单查询请求”应写成“尚未响应用户的订单查询”；“订单详情未向用户展示”应写成“尚未向用户展示订单详情”。
                同一事件只能出现在resolvedEvents或unresolvedEvents其中一个数组；状态变化后应从未处理移动到已处理。
                两组事件都不得复制聊天原话，不得回顾与当前诉求无关的旧问题。
                6. timeline按发生顺序总结最近的服务进展，最多5个节点。输入聊天达到5条时必须返回5个节点；不足5条时按实际可提炼数量返回。
                节点应覆盖用户诉求、用户补充信息、客服处理、明确承诺、状态变化、超时和风险升级；只有纯问候可以忽略。
                7. workflowStatus用4到14个汉字概括当前最新处理阶段或下一步动作，必须反映最新对话，不能沿用已经完成的旧状态。例如“等待用户上传图片”“图片已收到·等待核验”“赔偿处理中”“等待客服回复”。
                timeline的actor必须标明行为主体：用户消息填USER，客服消息填AGENT，业务状态变化填SYSTEM。
                timeline的label只填写简短的动作或结果，禁止复制、截取或改写聊天原句；系统最终会把actor转换成“用户”或“客服”前缀。
                例如用户询问赔款写成“用户提出赔偿”，客服说明1到3个工作日到账写成“客服告知到账时间”，用户同意方案写成“用户接受赔偿方案”。
                完全相同且没有新增信息的消息可以合并；不同时间发生的用户追问、客服答复和状态推进应分别保留，确保能看出处理过程。
                label控制在12个汉字以内；occurredAt使用该事件最后一次发生的真实时间，没有时间则留空；风险或超时节点的risk为true。
                不得编造输入中不存在的订单、工单、承诺或用户事实。
                8. immediateHandlingRequired表示是否仍有需要客服现在处理的事项。未答复的实质诉求、尚未处理的安全问题为true。
                已回复并正在等待用户补材料、或者仅等待未来明确承诺时间到来，不算现在立刻需要处理，填false；这些事项仍可保留在unresolvedEvents中，不代表已经完成。
                只返回以下JSON，不要输出Markdown：
                {
                  "contextSummary": "string",
                  "intents": ["string"],
                  "customerTags": ["string"],
                  "resolvedEvents": [{"userRequest": "用户提出的要求", "serviceResolution": "客服完成结果", "completedAt": "完成时间"}],
                  "unresolvedEvents": [{"description": "待处理事项", "sourceMessageId": "对应用户消息id"}],
                  "workflowStatus": "当前处理状态",
                  "immediateHandlingRequired": false,
                  "timeline": [{"type": "事件类型", "actor": "USER|AGENT|SYSTEM", "label": "动作或结果", "occurredAt": "真实时间", "risk": false}]
                }
                """, request, routedTier, usageSink);

        ContextWorkflowResponse response = new ContextWorkflowResponse(
                request.conversationId(),
                result.path("contextSummary").asText(),
                stringList(result.path("intents")),
                stringList(result.path("customerTags")),
                resolvedEventList(result.path("resolvedEvents")),
                unresolvedEventList(result.path("unresolvedEvents")),
                result.path("workflowStatus").asText(""),
                timelineList(result.path("timeline"), request),
                result.path("immediateHandlingRequired").isBoolean() ? result.path("immediateHandlingRequired").asBoolean() : null);
        if (request.conversationId() != null) {
            contextByConversation.put(request.conversationId(), new FingerprintedContext(fingerprint, response));
        }
        return response;
    }

    public RiskWorkflowResponse analyzeRisk(AssistanceContextRequest request) {
        return analyzeRisk(request, null);
    }

    public RiskWorkflowResponse analyzeRisk(
            AssistanceContextRequest originalRequest,
            Consumer<ModelUsage> usageSink) {
        return analyzeRisk(originalRequest, usageSink, false);
    }

    /** 评估对照用：跳过规则级联，强制调用大模型。 */
    public RiskWorkflowResponse analyzeRiskWithModel(AssistanceContextRequest request) {
        return analyzeRisk(request, null, true);
    }

    private RiskWorkflowResponse analyzeRisk(
            AssistanceContextRequest originalRequest,
            Consumer<ModelUsage> usageSink,
            boolean forceModel) {
        int ruleScore = calculateRuleScore(originalRequest);
        ContextCompressor.Result compressed = compressor.compress(originalRequest, ContextCompressor.Profile.RISK);
        AssistanceContextRequest request = compressed.request();
        // 所有新客户消息均进入risk节点，按配置选择模型；平稳/问候不再跳过模型。
        // 规则只补充风险保护，以及在模型不可用时显式降级。
        tokenMeter.recordTrim("risk", (long) Math.ceil(compressed.savedChars() / 1.5));

        JsonNode result;
        try {
            result = modelClient.generateJson("risk", """
                你是美妆电商客服辅助系统的语义情绪分析节点。
                只分析语义情绪、投诉倾向和情绪升级趋势，不判断业务政策。
                emotion只能是positive、neutral、negative、critical之一。
                emotionScore必须是0到100之间的整数，数值越高表示用户当前负面情绪越强。
                emotionTrend按时间顺序分析最近6条用户消息，每条消息生成一个情绪分数。
                如果输入中共有N条用户消息，emotionTrend必须返回min(N,6)个点，不能只返回当前消息。
                分析历史消息时必须只依据该条消息当时表达的内容独立评分，不能用后续消息的情绪反推或修改历史分数。
                emotionTrend中的time必须原样使用对应消息里的time，不得自行编造时间。
                semanticRiskScore必须是0到50之间的整数。
                evidence填写归纳后的风险结论，不得复制或改写用户原话。
                优先使用“明确投诉倾向”“情绪明显升级”“对处理进度不满”
                “重复催促”“安全事件信号”等简短分析标签。
                没有风险依据时返回空数组，不要把无意义数字、问候或普通问题作为证据。
                只返回以下JSON，不要输出Markdown：
                {
                  "emotion": "positive|neutral|negative|critical",
                  "emotionScore": 0,
                  "emotionTrend": [{"time": "消息时间", "score": 0}],
                  "semanticRiskScore": 0,
                  "evidence": ["string"]
                }
                """, request, ModelRouter.STANDARD, usageSink);
        } catch (IllegalStateException exception) {
            // 降级：模型不可用时仍按规则给出风险结论，安全事件照常强制升级，保证工作台可用。
            if (forceModel) throw exception;
            return ruleOnlyRisk(request, ruleScore, "RULE_FALLBACK");
        }

        int semanticScore = Math.max(
                0,
                Math.min(50, result.path("semanticRiskScore").asInt(0)));
        int riskScore = Math.min(100, ruleScore + semanticScore);
        boolean forcedHandoff = riskScore >= 80 || containsSafetyEvent(request);
        List<String> evidence = new ArrayList<>(ruleEvidence(request));
        evidence.addAll(
                stringList(result.path("evidence")).stream()
                        .filter(item -> !isRawMessageText(item, request))
                        .toList());

        String emotion = normalizeEmotion(result.path("emotion").asText("neutral"));
        int emotionScore = consistentScore(emotion, result.path("emotionScore").asInt(35));
        List<EmotionTrendPoint> trend = new ArrayList<>(emotionTrend(result.path("emotionTrend")));
        if (!trend.isEmpty()) {
            EmotionTrendPoint last = trend.get(trend.size() - 1);
            trend.set(trend.size() - 1, new EmotionTrendPoint(last.time(), emotionScore));
        }
        return new RiskWorkflowResponse(
                request.conversationId(),
                emotion,
                emotionScore,
                trend,
                riskScore,
                calculateQueueScore(riskScore, forcedHandoff),
                evidence.stream().distinct().toList(),
                forcedHandoff,
                forcedHandoff ? "FORCED_HANDOFF" : "NONE",
                forcedHandoff
                        ? "检测到高风险或安全事件，建议立即携带上下文转接人工。"
                        : "当前无需强制转人工。",
                OffsetDateTime.now().plusMinutes(10),
                "LLM");
    }

    static int consistentScore(String emotion, int value) {
        int score = Math.max(0, Math.min(100, value));
        return switch (emotion) {
            case "positive" -> Math.min(20, score);
            case "negative" -> Math.max(55, Math.min(79, score));
            case "critical" -> Math.max(80, score);
            default -> Math.max(21, Math.min(54, score));
        };
    }

    private RiskWorkflowResponse ruleOnlyRisk(AssistanceContextRequest request, int ruleScore, String mode) {
        List<EmotionTrendPoint> trend = new ArrayList<>();
        int latestScore = 30;
        if (request.messages() != null) {
            List<Map<String, Object>> messages = request.messages();
            for (Map<String, Object> message : messages.subList(Math.max(0, messages.size() - 6), messages.size())) {
                latestScore = RuleSignals.emotionScore(ContextCompressor.text(message));
                String time = Objects.toString(message.getOrDefault("time", message.getOrDefault("sentAt", "")), "");
                if (!time.isBlank()) trend.add(new EmotionTrendPoint(time, latestScore));
            }
        }
        if (request.latestMessage() != null && !request.latestMessage().isBlank()) {
            latestScore = RuleSignals.emotionScore(request.latestMessage());
        }
        // 降级时缺少语义分（最高 50），规则分上限为 70，因此“投诉 + 重复进线/未解决”（≥55）即视为需要升级。
        int threshold = "RULE_FALLBACK".equals(mode) ? 55 : 80;
        boolean forcedHandoff = ruleScore >= threshold || containsSafetyEvent(request);
        if (forcedHandoff) latestScore = Math.max(latestScore, 80);
        return new RiskWorkflowResponse(
                request.conversationId(),
                RuleSignals.emotionLevel(latestScore),
                latestScore,
                trend,
                ruleScore,
                calculateQueueScore(ruleScore, forcedHandoff),
                ruleEvidence(request),
                forcedHandoff,
                forcedHandoff ? "FORCED_HANDOFF" : "NONE",
                forcedHandoff
                        ? "检测到高风险或安全事件，建议立即携带上下文转接人工。"
                        : "当前无需强制转人工。",
                OffsetDateTime.now().plusMinutes(10),
                mode);
    }

    public ReplyDraftResponse generateReplyDraft(AssistanceContextRequest request) {
        return generateReplyDraft(request, null, null);
    }

    /**
     * insights 为工作流上游节点（图片核验、个性化推荐、风险）的结论，作为草稿的依据。
     */
    public ReplyDraftResponse generateReplyDraft(
            AssistanceContextRequest originalRequest,
            Map<String, Object> insights,
            Consumer<ModelUsage> usageSink) {
        ContextCompressor.Result compressed = compressor.compress(originalRequest, ContextCompressor.Profile.REPLY);
        AssistanceContextRequest request = compressed.request();
        tokenMeter.recordTrim("reply-draft", (long) Math.ceil(compressed.savedChars() / 1.5));
        Object input = insights == null || insights.isEmpty()
                ? request
                : Map.of("conversation", request, "insights", insights);

        JsonNode result = modelClient.generateJson("reply-draft", """
                你是美妆电商客服辅助系统的回复草稿节点。
                根据输入中的真实聊天、订单、工单和承诺生成客服回复草稿。
                要求：优先直接回答用户最新一条消息，只在回答当前问题确有必要时引用已经确认的事实；
                已经在历史客服回复中告知过用户的订单号、商品、规格、图片核实结果等信息不得重复复述；
                不要每轮都重新介绍事件背景，回复控制在1到3个简洁句子；
                用户主动请求产品推荐，且对话或输入未明确表明已完成肤质问卷时，先自然邀请填写一分钟问卷，不直接罗列商品。
                参考话术：“为了更好地了解您的肤质和护肤需求，请您点击聊天框上方的‘产品推荐·自测’，填写一分钟问卷，完成后我再为您推荐合适的产品。”
                此邀请只用文字指引现有按钮，不附加问卷URL、Markdown链接，不声称已发送链接或创建新入口。
                若已明确完成问卷，直接结合已有结果继续推荐，不重复要求填写；用户不愿填写时尊重选择，再根据已有信息提供帮助。
                只有当前诉求为产品推荐时才引导问卷，物流、投诉、退款或不良反应处理中不得插入问卷邀请。
                客服回复使用日常聊天式短句，用逗号、句号自然断句，不使用中文或英文分号，不把多层说明拼成书面化长句。
                不得编造处理结果；
                不得承诺输入中没有依据的时间；不得声称已经执行尚未执行的操作。
                必须分清当前问题的实际阶段，只回答用户本轮诉求，不能把规范里的其他条件分支拼进回复。
                用户说“未发货”时，只谈发货安排、延迟原因和下一步核实，不谈签收、代收、丢件或配送；
                “已发货运输中”和“签收未收到”是另外两种场景，只有当前对话或真实业务结果明确支持时才能使用。
                不得用“如果已签收……”等假设分支引入与当前诉求无关的问题；不把历史另一笔订单或旧问题的状态套到本轮。
                面向用户的回复必须全部使用自然、通顺的简体中文。
                采用正式、温和、尊重用户的真人客服语气，称呼用户一律使用“您”，不用“你”“亲”“宝子”等随意称呼。
                需要用户配合时使用“请您”“麻烦您”“方便的话，请您”等礼貌表达，不直接命令“发照片”“给订单号”“等着”。
                表达核实时优先说“我帮您核实”“请您稍等，我为您核对”，但不得声称已完成实际上未执行的查询或操作。
                回答疑问可以说“您好，关于您提到的……”，承接对话时不必每轮重复“您好”或自我介绍。
                敬语应自然，不必每句话都重复“您”，不得使用“尊敬的客户”等生硬称呼，不要堆砌感谢或奉承。
                以消费者的感受和实际困扰为出发点：遇到破损漏液、错发、反复催促、等待过久或投诉时，应主动诚恳致歉，
                并具体回应这件事带来的不便、失望或着急，再说明能够核实或推进的下一步，不能只说“抱歉给您带来困扰”。
                需要致歉时先说“很抱歉”或“实在抱歉”，再用一句话回应不便，随后直接说明下一步。
                例如“很抱歉，让您久等了。我先帮您核实为什么还没有发货，确认后再向您说明发货安排。”
                不要先复述一长串遭遇再在中间插入道歉，不要说“您的不满我完全理解”“您的不满我会认真对待”等口号。
                只在确有必要时称呼姓名，不要每轮都喊“某女士”，也不要反复报订单号。
                只回应聊天中有依据的感受，不替用户夸大损失，不说“我完全理解”，不争辩、不责怪用户，不用“请耐心等待”敷衍。
                用户再次表达不满或事情仍未解决时，可以再次自然致歉，但应结合新增困扰换一种具体表达，不能逐字重复上一轮。
                普通问候、商品咨询或正常进度确认不必强行道歉；道歉不是承认未经核实的责任，也不代表退款、补发或赔偿授权。
                不得把内部分析标签、字段名称或模型术语写进回复正文。
                应结合用户当前感受自然表达理解和共情，先回应人的处境，再回应事情；避免生硬翻译和空泛套话。
                商品型号、商品编码和订单号等业务标识可以保持原样。
                如果输入包含insights：其中的图片核验、个性化推荐和风险结论是系统已经核实的辅助信息，可以作为回复依据；
                推荐商品只能来自insights.recommendation.items，并用一句话说明为什么适合该用户，不得推荐其他商品；
                商品ingredientsVerified为false时，flags为空不代表无酒精/无香精等，不得宣称符合成分规避要求，必须提醒成分尚未核实、以包装为准；price为0表示价格未知，不能写成0元或免费；来源商品价只是采集时参考价，不承诺现价或库存。
                如果insights.recommendation.repeatedConcerns显示用户多次提到同一困扰，必须先正面回应这个困扰，再给建议；
                图片核验为皮肤不适时，不做医学诊断，只建议停用并就医，并说明会尽快安排专人跟进。
                insights.skills是处理参考，不是逐句复述的模板。只把当前对话仍适用的步骤自然融入回复，遵守安全与授权边界，禁止按关键词机械套话；learnedTips是经审核的经验要点。
                先理解整段上下文和用户最新补充：已提供的资料不再索要，已说明没有的资料不能继续强求，已缓解的症状不当作仍在恶化。
                用户说“没有就医凭证，但有过敏照片”，应明确理解没有凭证，承接照片这个可用材料，不得继续要求就医凭证，也不得把没有凭证等同于不能售后。
                用户提出产品问题而尚不能排除问题时，先诚恳道歉并回应实际体验，不得无依据声称产品没有问题或归咎于肤质。
                只有仍存在危险或新的安全问题时才补充相应安全提示，历史已告知的停用、就医、留存包装等整段话不应每轮重复。
                SOP是处理指引，不是操作已经完成的证据，也不代表已获退款、补发或赔偿授权；
                SOP中的方案和时间须以真实订单、政策及授权为准，信息不足时先核实，不得直接承诺。
                破损漏液问题不能只道歉：未提供照片时明确说明需要异常处和外包装照片、核实后确认方案；
                已索要但未收到时说明仍待照片，已收到时不再索要，说明核实下一步。
                approvedExamples是客服认可过的同类真实回复，只参考语气和结构，不得照抄其中的订单、商品或时间。
                只返回以下JSON，不要输出Markdown：
                {
                  "draft": "string",
                  "tone": "共情|平和|紧急",
                  "basedOn": ["string"]
                }
                """, input, ModelRouter.REPLY, usageSink);

        return new ReplyDraftResponse(
                request.conversationId(),
                sanitizeCustomerFacingDraft(SkillReplyGuard.enforce(originalRequest, insights, result.path("draft").asText())),
                result.path("tone").asText("共情"),
                stringList(result.path("basedOn")),
                true,
                OffsetDateTime.now().plusMinutes(10));
    }

    static String sanitizeCustomerFacingDraft(String draft) {
        if (draft == null || draft.isBlank()) return "";
        return draft
                .replace('；', '。')
                .replaceAll("(?<=[\\p{IsHan}，。！？])[ \\t]*;[ \\t]*(?=[\\p{IsHan}])", "。")
                .replaceAll("(?i)\\bfrustration\\b", "着急和不满")
                .replaceAll("(?i)\\bcritical\\b", "情绪非常强烈")
                .replaceAll("(?i)\\bnegative\\b", "负面情绪")
                .replaceAll("(?i)\\bneutral\\b", "情绪平稳")
                .replaceAll("(?i)\\bpositive\\b", "正面情绪");
    }

    private List<TimelineNode> buildTimeline(AssistanceContextRequest request) {
        List<TimelineNode> timeline = new ArrayList<>();
        addEvents(timeline, request.messages(), "MESSAGE", "time", "text");
        addEvents(timeline, request.workOrders(), "WORK_ORDER", "createdAt", "status");
        addEvents(timeline, request.commitments(), "COMMITMENT", "dueAt", "description");
        return timeline;
    }

    private List<TimelineNode> timelineList(
            JsonNode node,
            AssistanceContextRequest request) {
        if (!node.isArray() || node.isEmpty()) return List.of();
        List<TimelineNode> timeline = new ArrayList<>();
        node.forEach(item -> {
            String label = withTimelineActor(
                    item.path("label").asText("").trim(),
                    item.path("actor").asText(""),
                    item.path("type").asText(""));
            if (!label.isBlank()) {
                label = limitTimelineLabel(label);
                timeline.add(new TimelineNode(
                        item.path("type").asText("EVENT"),
                        label,
                        item.path("occurredAt").asText(""),
                        item.path("risk").asBoolean(false)));
            }
        });
        supplementTimeline(timeline, request);
        return timeline.stream().limit(5).toList();
    }

    private void supplementTimeline(List<TimelineNode> timeline, AssistanceContextRequest request) {
        if (request.messages() == null || request.messages().isEmpty()) return;
        int targetSize = Math.min(5, request.messages().size());
        if (timeline.size() >= targetSize) return;

        List<Map<String, Object>> messages = request.messages();
        for (int index = messages.size() - 1; index >= 0 && timeline.size() < targetSize; index--) {
            Map<String, Object> message = messages.get(index);
            String time = String.valueOf(message.getOrDefault("time", ""));
            String role = String.valueOf(message.getOrDefault("role", ""));
            boolean alreadyIncluded = timeline.stream().anyMatch(node ->
                    !time.isBlank() && time.equals(node.occurredAt()) &&
                            node.label().startsWith(timelineActorPrefix(role)));
            if (alreadyIncluded) continue;

            timeline.add(new TimelineNode(
                    role.equalsIgnoreCase("user") ? "USER_MESSAGE" : "AGENT_MESSAGE",
                    summarizeFallbackMessage(role, String.valueOf(message.getOrDefault("text", ""))),
                    time,
                    false));
        }
        timeline.sort(java.util.Comparator.comparing(TimelineNode::occurredAt));
    }

    private String timelineActorPrefix(String role) {
        return role.equalsIgnoreCase("user") ? "用户" : "客服";
    }

    private String summarizeFallbackMessage(String role, String text) {
        boolean user = role.equalsIgnoreCase("user");
        String prefix = user ? "用户" : "客服";
        String content = text == null ? "" : text;
        if (content.contains("退款") || content.contains("赔款")) {
            return limitTimelineLabel(prefix + (user ? "询问退款" : "说明退款进度"));
        }
        if (content.contains("补发")) {
            return limitTimelineLabel(prefix + (user ? "询问补发" : "说明补发进度"));
        }
        if (content.contains("发票")) {
            return limitTimelineLabel(prefix + (user ? "询问发票" : "说明开票进度"));
        }
        if (content.contains("到账") || content.matches(".*\\d+\\s*[-到至]\\s*\\d+.*")) {
            return limitTimelineLabel(prefix + (user ? "询问到账时间" : "告知到账时间"));
        }
        if (content.contains("投诉") || content.contains("12315")) {
            return limitTimelineLabel(prefix + (user ? "提出投诉" : "回应投诉诉求"));
        }
        return limitTimelineLabel(prefix + (user ? "补充信息" : "回复用户"));
    }

    private void addEvents(
            List<TimelineNode> target,
            List<Map<String, Object>> events,
            String type,
            String timeField,
            String labelField) {
        if (events == null) return;
        for (Map<String, Object> event : events) {
            String time = String.valueOf(event.getOrDefault(timeField, ""));
            String label = String.valueOf(event.getOrDefault(labelField, type));
            String actor = String.valueOf(event.getOrDefault("role", ""));
            label = limitTimelineLabel(withTimelineActor(label, actor, type));
            target.add(new TimelineNode(type, label, time, false));
        }
    }

    private String withTimelineActor(String label, String actor, String type) {
        if (label == null || label.isBlank()) return "";
        String normalized = label.trim();
        if (normalized.startsWith("用户") || normalized.startsWith("客服") || normalized.startsWith("系统")) {
            return normalized;
        }

        String source = (actor + " " + type).toUpperCase();
        if (source.contains("USER")) return "用户" + normalized;
        if (source.contains("AGENT") || source.contains("ASSISTANT") || source.contains("CUSTOMER_SERVICE")) {
            return "客服" + normalized;
        }
        return "系统" + normalized;
    }

    private String limitTimelineLabel(String label) {
        return label.length() > 12 ? label.substring(0, 12) : label;
    }

    private int calculateRuleScore(AssistanceContextRequest request) {
        String text = allMessageText(request).toLowerCase();
        int score = 0;
        if (text.contains("12315") || text.contains("投诉") || text.contains("曝光")) score += 35;
        if (text.contains("第三次") || text.contains("多次联系")) score += 20;
        if (text.contains("超时") || text.contains("没解决")) score += 20;
        if (containsSafetyEvent(request)) score += 40;
        return Math.min(70, score);
    }

    private List<String> ruleEvidence(AssistanceContextRequest request) {
        String text = allMessageText(request).toLowerCase();
        List<String> evidence = new ArrayList<>();
        if (text.contains("12315") || text.contains("投诉")) evidence.add("出现明确投诉关键词");
        if (text.contains("第三次") || text.contains("多次联系")) evidence.add("用户重复进线");
        if (text.contains("超时") || text.contains("没解决")) evidence.add("存在未解决或承诺超时信号");
        if (containsSafetyEvent(request)) evidence.add("出现不良反应或就医安全事件");
        return evidence;
    }

    private boolean containsSafetyEvent(AssistanceContextRequest request) {
        if (request.messages() != null) {
            for (Map<String, Object> message : request.messages()) {
                if ("user".equalsIgnoreCase(ContextCompressor.role(message))
                        && RuleSignals.isSafetyEvent(ContextCompressor.text(message))) return true;
            }
        }
        return RuleSignals.isSafetyEvent(request.latestMessage());
    }

    private String allMessageText(AssistanceContextRequest request) {
        StringBuilder text = new StringBuilder();
        if (request.latestMessage() != null) text.append(request.latestMessage()).append(' ');
        if (request.messages() != null) {
            for (Map<String, Object> message : request.messages()) {
                text.append(message.getOrDefault("text", "")).append(' ');
            }
        }
        return text.toString();
    }

    private boolean isRawMessageText(String evidence, AssistanceContextRequest request) {
        String normalizedEvidence = normalizeText(evidence);
        if (normalizedEvidence.length() < 4 || request.messages() == null) return false;

        return request.messages().stream()
                .map(message -> normalizeText(String.valueOf(message.getOrDefault("text", ""))))
                .anyMatch(message -> !message.isBlank() && message.contains(normalizedEvidence));
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("[\\s，。！？,.!?、\"“”]", "");
    }

    private int calculateQueueScore(int riskScore, boolean forcedHandoff) {
        return riskScore * 5 + (forcedHandoff ? 300 : 0);
    }

    private String normalizeEmotion(String emotion) {
        return switch (emotion) {
            case "positive", "neutral", "negative", "critical" -> emotion;
            default -> "neutral";
        };
    }

    private List<String> stringList(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    private List<UnresolvedEvent> unresolvedEventList(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<UnresolvedEvent> events = new ArrayList<>();
        node.forEach(item -> {
            String description = item.isTextual()
                    ? item.asText("")
                    : item.path("description").asText("");
            String sourceMessageId = item.isTextual()
                    ? ""
                    : item.path("sourceMessageId").asText("").trim();
            String normalizedDescription = normalizeUnresolvedEvent(description);
            if (!normalizedDescription.isBlank() && events.stream().noneMatch(
                    event -> event.description().equals(normalizedDescription))) {
                events.add(new UnresolvedEvent(normalizedDescription, sourceMessageId));
            }
        });
        return events.stream().limit(3).toList();
    }

    private List<ResolvedEvent> resolvedEventList(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<ResolvedEvent> events = new ArrayList<>();
        node.forEach(item -> {
            String userRequest = item.path("userRequest").asText("").trim();
            String serviceResolution = item.path("serviceResolution").asText("").trim();
            String completedAt = item.path("completedAt").asText("").trim();
            if (!userRequest.isBlank() && !serviceResolution.isBlank()) {
                events.add(new ResolvedEvent(userRequest, serviceResolution, completedAt));
            }
        });
        return events;
    }

    private String normalizeUnresolvedEvent(String event) {
        if (event == null) return "";
        String value = event.trim();
        value = value.replaceFirst("^用户尚未收到(.+)反馈$", "尚未向用户反馈$1");
        value = value.replaceFirst("^客服尚未", "尚未");
        value = value.replaceFirst("^(.+)未向用户(展示|说明|反馈|告知|提供)$", "尚未向用户$2$1");
        if (value.equals("尚未响应订单查询请求")) {
            return "尚未响应用户的订单查询";
        }
        return value;
    }

    private List<EmotionTrendPoint> emotionTrend(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<EmotionTrendPoint> points = new ArrayList<>();
        node.forEach(item -> {
            String time = item.path("time").asText("");
            int score = Math.max(0, Math.min(100, item.path("score").asInt(35)));
            if (!time.isBlank()) points.add(new EmotionTrendPoint(time, score));
        });
        return points.stream().limit(6).toList();
    }

    private List<String> userTexts(AssistanceContextRequest request) {
        List<String> texts = new ArrayList<>();
        if (request.messages() != null) {
            request.messages().stream()
                    .filter(message -> "user".equalsIgnoreCase(ContextCompressor.role(message)))
                    .map(ContextCompressor::text)
                    .forEach(texts::add);
        }
        if (request.latestMessage() != null) texts.add(request.latestMessage());
        int from = Math.max(0, texts.size() - 6);
        return texts.subList(from, texts.size());
    }

    /** 只对业务事实取指纹，不含 previousSummary，避免摘要回写导致重复调用。 */
    private String factsFingerprint(AssistanceContextRequest request) {
        return safeJson(List.of(
                Objects.requireNonNullElse(request.messages(), List.of()),
                Objects.requireNonNullElse(request.order(), Map.of()),
                Objects.requireNonNullElse(request.workOrders(), List.of()),
                Objects.requireNonNullElse(request.commitments(), List.of())));
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }

    private record FingerprintedContext(String fingerprint, ContextWorkflowResponse response) {
    }
}
