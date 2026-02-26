package com.jparlant.service.flow.handler.input.validation;

import com.jparlant.model.ValidationContext;

import java.util.List;

public interface FieldValidator {

    /**
     * @return 错误信息列表，如果为空则表示校验通过
     */
    List<String> validate(ValidationContext context);

}
