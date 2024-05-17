package ir.rumtung.BalanceKeeper;

public class Balance {
    private final int accountNo;
    private int amount;

    public Balance(int accNo, int amount){
        this.accountNo = accNo;
        this.amount = amount;
    }

    public void depositBalance(int newAmount){
        this.amount += newAmount;
    }
    public boolean withdrawBalance(int withdrawAmount){
        if((this.amount - withdrawAmount) < 0 ){
            return  false;
        }
        this.amount -= withdrawAmount;
        return true;
    }
    public int getBalance(){
        return this.amount;
    }
    public boolean accountBelongMe(int accNo){
        return this.accountNo==accNo;
    }
}
