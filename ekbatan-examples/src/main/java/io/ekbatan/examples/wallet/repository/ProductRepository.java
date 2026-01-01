package io.ekbatan.examples.wallet.repository;

import static io.ekbatan.examples.generated.jooq.public_schema.tables.Products.PRODUCTS;
import static io.ekbatan.examples.wallet.models.ProductBuilder.product;

import io.ekbatan.core.domain.GenericState;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.repository.EntityRepository;
import io.ekbatan.examples.generated.jooq.public_schema.tables.Products;
import io.ekbatan.examples.generated.jooq.public_schema.tables.records.ProductsRecord;
import io.ekbatan.examples.wallet.models.Product;
import java.util.UUID;

public class ProductRepository extends EntityRepository<Product, ProductsRecord, Products, UUID> {

    public ProductRepository(TransactionManager transactionManager) {
        super(Product.class, PRODUCTS, PRODUCTS.ID, transactionManager);
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
