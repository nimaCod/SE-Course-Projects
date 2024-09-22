package ir.ramtung.tinyme.domain.entity;

public class EntityObj {
    public Security security;
    public Shareholder shareholder;
    public Broker broker;
    public EntityObj(Security sec , Shareholder share , Broker br)
    {
        this.security = sec ;
        this.shareholder = share;
        this.broker = br;
    }
}
