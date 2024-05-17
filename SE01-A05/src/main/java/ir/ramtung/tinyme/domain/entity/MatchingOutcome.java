package ir.ramtung.tinyme.domain.entity;

public enum MatchingOutcome {
    EXECUTED,
    NOT_ENOUGH_CREDIT,
    NOT_ENOUGH_POSITIONS,
    MINIMUM_EXECUTION_QUANTITY_NOT_SATISFIED,

    STOP_LIMIT_ORDER_UPDATED_SUCCESSFUL
}
