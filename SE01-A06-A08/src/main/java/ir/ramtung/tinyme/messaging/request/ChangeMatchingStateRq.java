package ir.ramtung.tinyme.messaging.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class ChangeMatchingStateRq {
    private String securityIsin;
    private MatchingState state;
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime entryTime;
    public ChangeMatchingStateRq(String isin , MatchingState newState){
        securityIsin = isin;
        state = newState;
        entryTime = LocalDateTime.now();
    }
    public ChangeMatchingStateRq(String isin , MatchingState newState , LocalDateTime time){
        this(isin , newState);
        entryTime = time;
    }

    public static ChangeMatchingStateRq createChangeMatchingStateRq(String isin , MatchingState newState) {
        return new ChangeMatchingStateRq(isin , newState);
    }

    public static ChangeMatchingStateRq createChangeMatchingStateRq(String isin , MatchingState newState , LocalDateTime time) {
        return new ChangeMatchingStateRq(isin , newState , time);
    }

}
