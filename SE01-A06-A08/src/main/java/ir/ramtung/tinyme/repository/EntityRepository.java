package ir.ramtung.tinyme.repository;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import org.springframework.stereotype.Component;

@Component
public class EntityRepository {
    SecurityRepository securityRepository;
    BrokerRepository brokerRepository;
    ShareholderRepository shareholderRepository;
    public EntityRepository(SecurityRepository securityRepo ,BrokerRepository brokerRepo ,ShareholderRepository shareholderRepo)
    {
        this.securityRepository = securityRepo;
        this.brokerRepository = brokerRepo;
        this.shareholderRepository = shareholderRepo;
    }
    public Shareholder findShareholderById(long shareholderId) {
        return shareholderRepository.findShareholderById(shareholderId);
    }
    public Security findSecurityByIsin(String isin)
    {
        return securityRepository.findSecurityByIsin(isin);
    }
    public Broker findBrokerById(long brokerId) {
        return brokerRepository.findBrokerById(brokerId);
    }

    public EntityObj getWantedEntities(String isin , long shareholderId , long brokerId)
    {
         return new EntityObj(findSecurityByIsin(isin) , findShareholderById(shareholderId) , findBrokerById(brokerId));
    }

    public EntityObj getEntitiesOf(EnterOrderRq OrderRequest)
    {
        return new EntityObj(findSecurityByIsin(OrderRequest.getSecurityIsin())
                , findShareholderById(OrderRequest.getShareholderId()) , findBrokerById(OrderRequest.getBrokerId()));
    }

}
