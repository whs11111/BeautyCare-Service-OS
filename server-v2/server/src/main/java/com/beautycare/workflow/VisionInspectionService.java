package com.beautycare.workflow;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.beautycare.ai.ModelClient;
import com.beautycare.ai.ModelClient.ModelUsage;
import com.beautycare.ai.TokenMeter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 多模态售后图片核验：识别错发/色号不符、破损漏液、疑似皮肤不良反应等，并与订单商品比对。
 * 图片在发送前统一降采样，千问 VL 视觉 Token 约按 28×28 像素块计费，降采样可直接降低成本。
 */
@Service
public class VisionInspectionService {

    public static final Pattern ATTACHMENT_URL = Pattern.compile(
            "/api/attachments/([0-9a-fA-F-]{36})");
    private static final int MAX_IMAGES = 3;

    private final Path directory = Path.of(System.getProperty("user.home"), ".beautycare-service-os", "attachments");
    private final ModelClient modelClient;
    private final TokenMeter tokenMeter;
    private final ObjectMapper objectMapper;
    private final IssueRadar issueRadar;
    private final int maxSide;

    public VisionInspectionService(
            ModelClient modelClient,
            TokenMeter tokenMeter,
            ObjectMapper objectMapper,
            IssueRadar issueRadar,
            @Value("${ai.vision.max-side:768}") int maxSide) {
        this.modelClient = modelClient;
        this.tokenMeter = tokenMeter;
        this.objectMapper = objectMapper;
        this.issueRadar = issueRadar;
        this.maxSide = maxSide;
    }

    /** 从任意结构的消息中提取图片附件地址（兼容前端不同的附件字段）。 */
    public List<String> findImageUrls(List<Map<String, Object>> messages) {
        List<String> urls = new ArrayList<>();
        if (messages == null) return urls;
        for (Map<String, Object> message : messages) {
            if (!"user".equalsIgnoreCase(ContextCompressor.role(message))) continue;
            String raw;
            try {
                raw = objectMapper.writeValueAsString(message);
            } catch (Exception exception) {
                continue;
            }
            Matcher matcher = ATTACHMENT_URL.matcher(raw);
            while (matcher.find()) {
                String url = "/api/attachments/" + matcher.group(1);
                if (!urls.contains(url) && isImage(matcher.group(1))) urls.add(url);
            }
        }
        int from = Math.max(0, urls.size() - MAX_IMAGES);
        return new ArrayList<>(urls.subList(from, urls.size()));
    }

    public VisionResult inspect(
            Long conversationId,
            List<String> imageUrls,
            Map<String, Object> order,
            String latestMessage,
            Consumer<ModelUsage> usageSink) {
        List<String> dataUrls = new ArrayList<>();
        for (String url : imageUrls) {
            Matcher matcher = ATTACHMENT_URL.matcher(url);
            if (!matcher.find()) continue;
            try {
                dataUrls.add(toDownscaledDataUrl(matcher.group(1)));
            } catch (IOException ignored) {
                // 无法读取的附件跳过，不影响其他图片。
            }
            if (dataUrls.size() >= MAX_IMAGES) break;
        }
        if (dataUrls.isEmpty()) {
            return VisionResult.none("没有可识别的图片附件");
        }
        if (!modelClient.visionConfigured()) {
            return new VisionResult("UNCLEAR", "未配置视觉模型，图片需人工查看", "", "UNKNOWN", "LOW", List.of(),
                    "请客服人工查看图片；配置 DASHSCOPE_API_KEY 后可自动核验", true, dataUrls.size(), "UNAVAILABLE");
        }

        String text;
        try {
            text = objectMapper.writeValueAsString(Map.of(
                    "order", order == null ? Map.of() : order,
                    "userMessage", latestMessage == null ? "" : latestMessage));
        } catch (Exception exception) {
            text = "{}";
        }

        JsonNode result = modelClient.generateVisionJson("vision", """
                你是美妆电商售后图片核验节点。根据用户上传的图片、订单信息和用户描述判断：
                issueType只能是WRONG_ITEM（商品或色号与订单不符）、DAMAGED（外包装或瓶身破损）、LEAKAGE（漏液）、
                SKIN_REACTION（疑似皮肤不适）、QUALITY（膏体变质、异物等品质问题）、NONE（图片未见问题）、UNCLEAR（图片无法判断）之一。
                observedProduct填写图片中可辨认的品类、品名或色号，看不清写“无法辨认”，不得编造。
                matchesOrder只能是YES、NO、UNKNOWN；订单中没有商品信息时必须为UNKNOWN。
                severity只能是LOW、MEDIUM、HIGH；SKIN_REACTION一律为HIGH。
                皮肤相关图片不做医学诊断，只描述可见现象，needsHumanReview必须为true。
                evidence最多3条，写图片中可见的客观依据。
                suggestedAction用一句话给客服下一步建议，例如“建议创建错发补发工单”“建议引导用户停用并转专人跟进”。
                只返回以下JSON，不要输出Markdown：
                {"issueType":"","observedProduct":"","matchesOrder":"UNKNOWN","severity":"LOW","evidence":[""],"suggestedAction":"","needsHumanReview":false}
                """, text, dataUrls, usageSink);

        String issueType = normalize(result.path("issueType").asText("UNCLEAR"),
                List.of("WRONG_ITEM", "DAMAGED", "LEAKAGE", "SKIN_REACTION", "QUALITY", "NONE", "UNCLEAR"), "UNCLEAR");
        String severity = "SKIN_REACTION".equals(issueType) ? "HIGH"
                : normalize(result.path("severity").asText("LOW"), List.of("LOW", "MEDIUM", "HIGH"), "LOW");
        List<String> evidence = new ArrayList<>();
        result.path("evidence").forEach(item -> {
            if (!item.asText("").isBlank() && evidence.size() < 3) evidence.add(item.asText());
        });

        VisionResult vision = new VisionResult(
                issueType,
                label(issueType),
                result.path("observedProduct").asText("无法辨认"),
                normalize(result.path("matchesOrder").asText("UNKNOWN"), List.of("YES", "NO", "UNKNOWN"), "UNKNOWN"),
                severity,
                evidence,
                result.path("suggestedAction").asText(""),
                "SKIN_REACTION".equals(issueType) || result.path("needsHumanReview").asBoolean(false),
                dataUrls.size(),
                "VLM");

        if (!List.of("NONE", "UNCLEAR").contains(issueType)) {
            issueRadar.report(IssueRadar.skuOf(order), IssueRadar.productNameOf(order), issueType, conversationId, "图片核验");
        }
        return vision;
    }

    private boolean isImage(String id) {
        try {
            Path typeFile = directory.resolve(id + ".type");
            return Files.exists(typeFile) && Files.readString(typeFile).startsWith("image/");
        } catch (IOException exception) {
            return false;
        }
    }

    private String toDownscaledDataUrl(String id) throws IOException {
        Path path = directory.resolve(id);
        BufferedImage source = ImageIO.read(path.toFile());
        if (source == null) throw new IOException("不是可解析的图片");

        int width = source.getWidth();
        int height = source.getHeight();
        double scale = Math.min(1.0, (double) maxSide / Math.max(width, height));
        int targetWidth = Math.max(28, (int) Math.round(width * scale));
        int targetHeight = Math.max(28, (int) Math.round(height * scale));

        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        graphics.dispose();

        // 千问 VL 默认会把超大图片压到约 1280 个视觉 Token，以此作为未降采样时的上限估算。
        long originalTokens = Math.min(1280, (long) Math.ceil(width / 28.0) * (long) Math.ceil(height / 28.0));
        long scaledTokens = (long) Math.ceil(targetWidth / 28.0) * (long) Math.ceil(targetHeight / 28.0);
        tokenMeter.recordTrim("vision", Math.max(0, originalTokens - scaledTokens));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(target, "jpg", output);
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private String normalize(String value, List<String> allowed, String fallback) {
        String upper = value == null ? "" : value.trim().toUpperCase();
        return allowed.contains(upper) ? upper : fallback;
    }

    public static String label(String issueType) {
        return switch (issueType) {
            case "WRONG_ITEM" -> "商品/色号与订单不符";
            case "DAMAGED" -> "包装或瓶身破损";
            case "LEAKAGE" -> "漏液";
            case "SKIN_REACTION" -> "疑似皮肤不适";
            case "QUALITY" -> "疑似品质问题";
            case "NONE" -> "图片未见异常";
            default -> "图片无法判断";
        };
    }

    public record VisionResult(
            String issueType,
            String issueLabel,
            String observedProduct,
            String matchesOrder,
            String severity,
            List<String> evidence,
            String suggestedAction,
            boolean needsHumanReview,
            int imagesAnalyzed,
            String mode) {

        static VisionResult none(String reason) {
            return new VisionResult("NONE", reason, "", "UNKNOWN", "LOW", List.of(), "", false, 0, "SKIPPED");
        }
    }
}
