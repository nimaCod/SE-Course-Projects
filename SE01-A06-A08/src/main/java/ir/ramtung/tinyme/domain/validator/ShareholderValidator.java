package ir.ramtung.tinyme.domain.validator;

import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.EntityRepository;

public class ShareholderValidator {
    public static boolean checkShareHolderIsAvailable(EnterOrderRq enterOrderRq, EntityRepository en) {
        return en.findShareholderById(enterOrderRq.getShareholderId()) == null;
    }
}
