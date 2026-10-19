package com.asea.common.exceptions;

public class BudgetExceededException extends AseaException {

    public BudgetExceededException(String message) {
        super("BUDGET_EXCEEDED", message);
    }
}
