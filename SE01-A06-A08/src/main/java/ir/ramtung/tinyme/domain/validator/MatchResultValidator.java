package ir.ramtung.tinyme.domain.validator;
import ir.ramtung.tinyme.domain.entity.MatchResult;
public class MatchResultValidator {
    public static boolean checkMatchResultHaveAnyTrades(MatchResult matchResult) {
        return matchResult.trades().isEmpty();
    }

}
