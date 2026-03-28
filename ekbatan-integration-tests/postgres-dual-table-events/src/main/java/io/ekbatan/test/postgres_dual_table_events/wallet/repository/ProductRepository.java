package io.ekbatan.test.postgres_dual_table_events.wallet.repository;

import static io.ekbatan.test.postgres_dual_table_events.generated.jooq.public_schema.tables.Products.PRODUCTS;
import static io.ekbatan.test.postgres_dual_table_events.wallet.models.ProductBuilder.product;

import io.ekbatan.core.domain.GenericState;
import io.ekbatan.core.repository.EntityRepository;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.test.postgres_dual_table_events.generated.jooq.public_schema.tables.Products;
import io.ekbatan.test.postgres_dual_table_events.generated.jooq.public_schema.tables.records.ProductsRecord;
import io.ekbatan.test.postgres_dual_table_events.wallet.models.Product;
import java.util.UUID;

public class ProductRepository extends EntityRepository<Product, ProductsRecord, Products, UUID> {

    public ProductRepository(DatabaseRegistry databaseRegistry) {
        super(Product.class, PRODUCTS, PRODUCTS.ID, databaseRegistry);
    }

    @Override
    public Product fromRecord(ProductsRecord record) {
        return product()
                .id(record.getId())
                .version(record.getVersion())
                .state(GenericState.valueOf(record.getState()))
                .name(record.getName())
                .price(record.getPrice())
                .build();
    }

    @Override
    public ProductsRecord toRecord(Product entity) {
        return new ProductsRecord(entity.id, entity.version, entity.state.name(), entity.name, entity.price);
    }
}
