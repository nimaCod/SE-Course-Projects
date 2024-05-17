package ir.rumtung;

import  java.util.ArrayList;

import static java.lang.Integer.sum;


public class Membership {
    private final String teamName;
    private final Date startDate;
    private final Date endDate;

    public Membership(String teamName, Date startDate, Date endDate) {
        // Make some checks
        if (startDate == null || endDate == null || startDate.compareTo(endDate) > 0) {
            throw new IllegalArgumentException("Invalid date for membership!");
        }

        this.teamName = teamName;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public boolean isOverlap(Membership membership) {
        return this.startDate.compareTo(membership.endDate) <= 0 && this.endDate.compareTo(membership.startDate) >= 0;
    }

    public int getMembershipDurationInDays() {
        Date next = this.startDate.nextDay();
        int count = 1;
        while(next.compareTo(this.endDate) !=0) {
            next = next.nextDay();
            count++;
        }
        return 0;
    }
    public Boolean isInTeam(String temName){
        return this.teamName.equals(temName);
    }
}

class Player {
    private String playerName;
    private ArrayList<Membership> membershipHistory;

    public Player(String playerName) {
        this.playerName = playerName;
        this.membershipHistory = new ArrayList<>();
    }

    public void addMembership(Membership newMembership) {
        for (Membership membership : membershipHistory) {
            if (membership.isOverlap(newMembership)) {
                throw new IllegalArgumentException("New membership overlaps with existing memberships!");
            }
        }

        membershipHistory.add(newMembership);
    }
    public int getMembershipDurationInDay(String nam){
        return this.membershipHistory.stream().filter(membership -> membership.isInTeam(nam)).map(Membership::getMembershipDurationInDays).mapToInt(Integer::intValue).sum();
    }
}

