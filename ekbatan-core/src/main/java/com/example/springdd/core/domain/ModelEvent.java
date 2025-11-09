package com.example.springdd.core.domain;

import java.io.Serializable;
import org.apache.commons.lang3.Validate;

public abstract class ModelEvent<MODEL> implements Serializable {
    public final Object modelId;
    public final String modelName;

    protected ModelEvent(String modelId, Class<MODEL> modelClass) {
        this.modelId = Validate.notBlank(modelId, "modelId cannot be null or blank");
        this.modelName =
                Validate.notNull(modelClass, "modelClass cannot be null").getSimpleName();
    }
}
