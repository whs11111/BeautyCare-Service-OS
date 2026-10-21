package com.beautycare.workflow;

import com.beautycare.api.*;
import com.beautycare.ai.*;
import com.beautycare.personalization.*;
import com.beautycare.skills.SkillRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/** Browser QA only: isolated home, empty model keys, loopback non-production port. */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = { "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration" })
@Import({ ReceptionController.class, ReceptionSnapshotAdvice.class, ReceptionBot.class, AgentController.class, ChatController.class,
        QuizController.class, SkinQuizService.class, PersonalizationService.class, CustomerProfileStore.class,
        ProductCatalog.class, ModelClient.class, ModelRouter.class, TokenMeter.class, TrainingRecorder.class,
        AssistanceWorkflowAiService.class, ContextCompressor.class, SkillRegistry.class })
public class ReceptionPreviewApplication {
    public static void main(String[] args) { SpringApplication.run(ReceptionPreviewApplication.class, args); }
}
