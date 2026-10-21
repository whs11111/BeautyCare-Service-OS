package com.beautycare.workflow;

import com.beautycare.api.QuizController;
import com.beautycare.personalization.*;
import com.beautycare.ai.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/** Isolated browser-test server. Launch with a temporary user.home, no keys, and a non-production port. */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = { "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration" })
@Import({ QuizController.class, SkinQuizService.class, PersonalizationService.class, CustomerProfileStore.class,
        ProductCatalog.class, ModelClient.class, ModelRouter.class, TokenMeter.class, TrainingRecorder.class })
public class QuizPreviewApplication {
    public static void main(String[] args) { SpringApplication.run(QuizPreviewApplication.class, args); }
}
