package io.ekbatan.test.postgres_single_table_events.wallet.models;

import io.ekbatan.core.domain.Entity;
import io.ekbatan.core.domain.GenericState;
import io.ekbatan.core.processor.AutoBuilder;
import java.math.BigDecimal;
import java.util.UUID;
import org.apache.commons.lang3.Validate;

@AutoBuilder
public final class Product extends Entity<Product, UUID, GenericState> {

    public final String name;
    public final BigDecimal price;

    Product(ProductBuilder builder) {
        super(builder);
        this.name = Validate.notNull(builder.name, "name cannot be null");
        this.price = builder.price;
    }

    public static ProductBuilder createProduct(String name, BigDecimal price) {
        final var id = UUID.randomUUID();
        return ProductBuilder.product()
                .id(id)
                .state(GenericState.ACTIVE)
                .name(name)
                .price(price)
                .withInitialVersion();
    }

    public ProductBuilder copy() {
        return ProductBuilder.product().copyBase(this).name(name).price(price);
    }

    public Product delete() {
        if (this.state.equals(GenericState.DELETED)) {
            return this;
        }

        return copy().state(GenericState.DELETED).build();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;
        if (!super.equals(other)) return false;
        Product product = (Product) other;
        return name.equals(product.name) && price.compareTo(product.price) == 0;
    }
}
