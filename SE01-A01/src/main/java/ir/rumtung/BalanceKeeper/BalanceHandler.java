package ir.rumtung.BalanceKeeper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

@Service
public class BalanceHandler {

    private ArrayList<Balance> BalanceList;
    @Autowired
    public BalanceHandler(ArrayList<Balance> balanceList) {
        this.BalanceList = balanceList;
    }

    public final static String DEPOSIT = "DEPOSIT";
    public final static String WITHDRAW = "WITHDRAW";
    public final static String BALANCE = "BALANCE";
    public final static String DepositSuccessMsg = "0 Deposit successful";
    public final static String WithdrawSuccessMsg = "0 Withdraw successful";
    public final static String WithdrawInsufficientMsg = "1 Insufficient funds";
    public final static String WithdrawUnknownMsg = "2 Unknown account number";
    public final static String BalanceSuccessMsg = "0 Balance: ";
    public final static String BalanceUnknownMsg = "2 Unknown account number";

    private String handleDeposit(int accNo,int amount){
        for(Balance balance:BalanceList){
            if(balance.accountBelongMe(accNo)){
                balance.depositBalance(amount);
                System.out.println(DepositSuccessMsg);
                return DepositSuccessMsg;
            }
        }
        BalanceList.add(new Balance(accNo,amount));
        System.out.println(DepositSuccessMsg);
        return DepositSuccessMsg;
    }

    private String handleWithdraw(int accNo,int amount){
        for(Balance balance:BalanceList){
            if(balance.accountBelongMe(accNo)){
                if(balance.withdrawBalance(amount)){
                    System.out.println(WithdrawSuccessMsg);
                    return WithdrawSuccessMsg;
                } else{
                    System.out.println(WithdrawInsufficientMsg);
                    return WithdrawInsufficientMsg;
                }
            }
        }
        System.out.println(WithdrawUnknownMsg);
        return WithdrawUnknownMsg;

    }

    private String handleBalance(int accNo){
        for(Balance balance:BalanceList){
            if(balance.accountBelongMe(accNo)){
                int blnc = balance.getBalance();
                System.out.println(BalanceSuccessMsg + blnc);
                return(BalanceSuccessMsg + blnc);
            }
        }
        System.out.println(BalanceUnknownMsg);
        return BalanceUnknownMsg;
    }

    public String handle(String message){
        String[] msgList = message.split(" ");
        int accNo = Integer.parseInt(msgList[1]);
        int amount;
        return switch (msgList[0]) {
            case DEPOSIT -> {
                amount = Integer.parseInt(msgList[2]);
                yield this.handleDeposit(accNo, amount);
            }
            case WITHDRAW -> {
                amount = Integer.parseInt(msgList[2]);
                yield this.handleWithdraw(accNo, amount);
            }
            case BALANCE -> this.handleBalance(accNo);
            default -> "2";
        };
    }
}


