package com.beautycare.personalization;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** 默认使用来源可追溯的商品快照；catalog.json 仅保留为隔离测试数据。 */
@Component
public class ProductCatalog {

    private final List<Product> products;

    public ProductCatalog(ObjectMapper objectMapper) throws IOException {
        this(objectMapper, System.getProperty("catalog.files", "catalog-paris-loreal.json"));
    }

    @Autowired
    public ProductCatalog(ObjectMapper objectMapper, @Value("${catalog.files:catalog-paris-loreal.json}") String file) throws IOException {
        try (InputStream input = new ClassPathResource(file).getInputStream()) {
            this.products = List.copyOf(objectMapper.readValue(input, new TypeReference<List<Product>>() {}));
        }
    }

    public List<Product> all() {
        return products;
    }

    public Optional<Product> find(String sku) {
        return products.stream().filter(product -> product.sku().equalsIgnoreCase(sku)).findFirst();
    }

    public record Product(
            String sku,
            String name,
            String category,
            int price,
            boolean hot,
            List<String> skinTypes,
            List<String> concerns,
            List<String> flags,
            List<String> highlights,
            String finish,
            String brand,
            String source,
            Boolean ingredientsVerified) {
        public boolean verifiedIngredients() {
            // Legacy source-free fixtures contain fully annotated fictional formulas.
            return ingredientsVerified == null ? source == null : ingredientsVerified;
        }
    }
}
