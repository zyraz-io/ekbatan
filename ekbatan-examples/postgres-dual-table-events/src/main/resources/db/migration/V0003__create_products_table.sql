-- Create the products table
CREATE TABLE products (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    name VARCHAR(255) NOT NULL,
    price DECIMAL(10, 2) NOT NULL
);