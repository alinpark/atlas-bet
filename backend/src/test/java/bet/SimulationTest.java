package bet;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class SimulationTest {
    BigDecimal n(String v){return new BigDecimal(v);}
    Instant start=Instant.parse("2026-11-01T10:00:00Z");
    Simulation.Limits limits=new Simulation.Limits(n("100"),n("500"),n("250"),n("2000"));
    Simulation.Candidate bet(String id,int minute,int result,String winner){return new Simulation.Candidate(id,id,"soccer_epl","H","A","home","test",n("2.5"),start.plusSeconds(minute*60),start.plusSeconds((minute+10)*60),result<0?null:start.plusSeconds(result*60),winner,false,true);}
    Map<String,Object> run(List<Simulation.Candidate> bets,String capital,String amount,boolean proportional){return Simulation.replay(bets,n(capital),n(amount),proportional,limits,start,start.plusSeconds(7200));}
    Map<?,?> summary(Map<String,Object> data){return (Map<?,?>)data.get("summary");}
    void eq(String expected,Object value){assertEquals(0,n(expected).compareTo((BigDecimal)value));}
    @Test void distinguishesReturnsProfitAndOpenCapital(){
        var s=summary(run(List.of(bet("a",0,30,"home"),bet("b",1,40,"away"),bet("c",2,-1,null)),"100","10",false));
        eq("25",s.get("gross_returns"));eq("5",s.get("net"));eq("105",s.get("capital"));eq("95",s.get("available"));eq("10",s.get("exposure"));eq("0.25",s.get("roi"));eq("10",s.get("max_drawdown"));eq("-5",s.get("worst_case_net"));
    }
    @Test void futureWinCannotFundEarlierBet(){
        limits=new Simulation.Limits(n("100"),n("1000"),n("1000"),n("2000"));
        var s=summary(run(List.of(bet("a",0,30,"home"),bet("b",1,40,"home")),"10","10",false));
        assertEquals(1,s.get("skipped"));eq("15",s.get("net"));eq("10",s.get("staked"));
    }
    @Test void proportionalUsesOnlyCapitalKnownAtEntry(){
        var d=run(List.of(bet("b",40,60,"away"),bet("a",0,30,"home")),"100","10",true);
        var rows=(List<Map<String,Object>>)d.get("bets");eq("10",rows.get(0).get("stake"));eq("11.50",rows.get(1).get("stake"));eq("3.50",summary(d).get("net"));
    }
    @Test void lossesDoNotRechargeAndMonthResets(){
        limits=new Simulation.Limits(n("100"),n("100"),n("100"),n("10"));
        var s=summary(run(List.of(bet("a",0,30,"away"),bet("b",40,60,"home")),"100","10",false));
        assertEquals(1,s.get("skipped"));eq("-10",s.get("net"));
        start=Instant.parse("2026-11-30T22:00:00Z");
        s=summary(run(List.of(bet("a",0,30,"away"),bet("b",70,90,"home")),"100","10",false));
        assertEquals(0,s.get("skipped"));eq("5",s.get("net"));
    }
    @Test void limitsReduceExposureAndVoidReturnsOnlyStake(){
        limits=new Simulation.Limits(n("15"),n("20"),n("250"),n("2000"));
        var a=bet("a",0,30,null);a=new Simulation.Candidate(a.id(),a.event(),a.competition(),a.home(),a.away(),a.outcome(),a.book(),a.odds(),a.at(),a.starts(),a.resolved(),null,true,true);
        var d=run(List.of(a,bet("b",1,-1,null)),"100","30",false);var s=summary(d);
        eq("20",s.get("staked"));eq("15",s.get("gross_returns"));eq("0",s.get("net"));eq("5",s.get("exposure"));assertEquals(1,s.get("void"));
    }
    @Test void missingAndBeyondEndResultsStayOpenAndEmptyHasNoRoi(){
        var s=summary(run(List.of(bet("a",0,180,"home")),"100","10",false));assertEquals(1,s.get("open"));eq("0",s.get("net"));assertNull(s.get("roi"));
        s=summary(run(List.of(),"100","10",false));eq("100",s.get("capital"));assertNull(s.get("roi"));
    }
    @Test void staleLateAndDuplicateEntriesAreExplicitlyExcluded(){
        var a=bet("a",0,-1,null);
        var stale=new Simulation.Candidate("stale","stale",a.competition(),a.home(),a.away(),a.outcome(),a.book(),a.odds(),a.at(),a.starts(),null,null,false,false);
        var late=new Simulation.Candidate("late","late",a.competition(),a.home(),a.away(),a.outcome(),a.book(),a.odds(),a.at(),a.at(),null,null,false,true);
        var duplicate=new Simulation.Candidate("duplicate",a.event(),a.competition(),a.home(),a.away(),a.outcome(),a.book(),a.odds(),a.at().plusSeconds(1),a.starts(),null,null,false,true);
        var s=summary(run(List.of(a,stale,late,duplicate),"100","10",false));assertEquals(3,s.get("skipped"));assertEquals(1,s.get("open"));eq("10",s.get("staked"));
    }
}
