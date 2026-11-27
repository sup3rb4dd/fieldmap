import app.fieldmap.core.*;
import java.io.StringReader;
import java.util.*;
public class RepeaterTest {
  static int fails=0;
  static void check(boolean ok,String msg){ if(!ok){System.out.println("FAIL "+msg);fails++;} }
  public static void main(String[] a) throws Exception {
    // A slice of HearHam's real feed (https://hearham.com/api/repeaters/v1), with a duplicate, escapes and an empty entry.
    String feed="[{\"id\":1,\"callsign\":\"VE7RHS\",\"latitude\":49.26973,\"longitude\":-123.24992,\"city\":\"Vancouver, BC Canada\",\"group\":\"IRLP\",\"internet_node\":\"1000\",\"mode\":\"FM\",\"encode\":\"100.0\",\"decode\":\"100.0\",\"frequency\":145270000,\"offset\":-600000,\"description\":\"Internet-connected IRLP node owned by VE7LTD.\\nhttps:\\/\\/www.ars.ams.ubc.ca\",\"power\":\"unknown\",\"operational\":1,\"restriction\":\"\"},\n"
      +"{\"id\":179,\"callsign\":\"W5DEL\",\"latitude\":35.446651,\"longitude\":-97.449544,\"city\":\"Oklahoma City, OK USA\",\"group\":\"IRLP\",\"internet_node\":\"3013\",\"mode\":\"FM\",\"encode\":\"103.5\",\"decode\":\"103.5\",\"frequency\":146700000,\"offset\":-600000,\"description\":\"Del City club \\u00e9t\\u00e9\",\"power\":\"unknown\",\"operational\":0,\"restriction\":\"\"},\n"
      +"{\"id\":180,\"callsign\":\"w5del\",\"latitude\":35.4467,\"longitude\":-97.4495,\"city\":\"Oklahoma City, OK USA\",\"group\":\"IRLP\",\"internet_node\":null,\"mode\":\"FM\",\"encode\":\"103.5\",\"decode\":\"103.5\",\"frequency\":146700000,\"offset\":-600000,\"description\":\"dup\",\"power\":\"unknown\",\"operational\":1,\"restriction\":\"\"},\n"
      +"{\"id\":989,\"callsign\":\"WD5M\",\"latitude\":35.237,\"longitude\":-98.5,\"city\":\"Albert, OK USA\",\"group\":\"\",\"internet_node\":null,\"mode\":\"DMR    \",\"encode\":\"DCS712\",\"decode\":\"DCS712\",\"frequency\":145110000,\"offset\":-600000,\"description\":\"\",\"power\":\"unknown\",\"operational\":1,\"restriction\":\"Club members\"},\n"
      +"{\"id\":5,\"callsign\":\"X\",\"latitude\":0,\"longitude\":0,\"city\":\"\",\"group\":\"\",\"internet_node\":null,\"mode\":\"FM\",\"encode\":\"0.00\",\"decode\":\"\",\"frequency\":0,\"offset\":0,\"description\":\"\",\"power\":\"\",\"operational\":1,\"restriction\":\"\"},\n"
      +"{\"id\":6,\"callsign\":\"WRXX999\",\"latitude\":35.6,\"longitude\":-97.5,\"city\":\"Edmond, OK\",\"group\":\"\",\"internet_node\":null,\"mode\":\"NFM\",\"encode\":\"141.3\",\"decode\":\"0.00\",\"frequency\":462650000,\"offset\":5000000,\"description\":\"GMRS\",\"power\":\"\",\"operational\":1,\"restriction\":\"\"}]";
    Object[] ok=Repeaters.state("OK");
    check(ok!=null&&ok[1].equals("Oklahoma"),"state lookup");
    List<Repeaters.Repeater> rs=Repeaters.parseHearHam(new StringReader(feed),"OK",(la,lo)->Repeaters.inState(ok,la,lo));
    check(rs.size()==3,"oklahoma only, no dup, no empty: "+rs.size());
    Repeaters.Repeater del=rs.get(0);
    check(del.id.equals("hh:179")&&del.callsign.equals("W5DEL"),"id/call "+del.id+" "+del.callsign);
    check(del.outHz==146_700_000L&&del.inHz()==146_100_000L,"in/out "+del.inHz());
    check(Repeaters.mhz(del.outHz).equals("146.700")&&Repeaters.mhz(del.inHz()).equals("146.100"),"mhz "+Repeaters.mhz(del.outHz));
    check(Repeaters.offset(del.offsetHz).equals("-0.600"),"offset "+Repeaters.offset(del.offsetHz));
    check(del.toneTx.equals("103.5")&&Repeaters.toneText(del.toneTx).equals("103.5 Hz"),"tone "+del.toneTx);
    check(del.notes.startsWith("Del City club été")&&del.notes.contains("IRLP node 3013"),"notes "+del.notes);
    check(del.status==0,"status off-air");
    Repeaters.Repeater wd=rs.get(1);
    check(wd.toneTx.equals("D712")&&Repeaters.toneText(wd.toneTx).equals("DCS 712"),"dcs "+wd.toneTx);
    check(wd.mode.equals("DMR")&&wd.notes.contains("Restriction: Club members"),"mode/restriction "+wd.mode+" "+wd.notes);
    Repeaters.Repeater g=rs.get(2);
    check(g.gmrs()&&g.toneRx.equals("")&&Repeaters.mhz(g.inHz()).equals("467.650"),"gmrs "+g.gmrs()+" "+Repeaters.mhz(g.inHz()));
    check(!del.gmrs(),"ham is not gmrs");
    check(Repeaters.parseHearHam(new StringReader("[]"),"OK",(la,lo)->true).isEmpty(),"empty list");
    try { Repeaters.parseHearHam(new StringReader("<html>"),"OK",(la,lo)->true); check(false,"html should fail"); } catch (java.io.IOException e) { }
    try { Repeaters.parseHearHam(new StringReader(feed.substring(0,900)),"OK",(la,lo)->true); check(false,"cut-off list should fail"); } catch (java.io.IOException e) { }

    // tones and frequencies
    check(Repeaters.tone("100.00").equals("100.0")&&Repeaters.tone("0.00").equals("")&&Repeaters.tone("").equals(""),"tone clean");
    check(Repeaters.tone("D23N").equals("D023")&&Repeaters.tone("DCS 023").equals("D023")&&Repeaters.tone("88.5 Hz").equals("88.5"),"tone forms");
    check(Repeaters.mhz(146_985_000L).equals("146.985")&&Repeaters.mhz(462_562_500L).equals("462.5625"),"mhz digits");
    check(Repeaters.offset(0).equals("simplex")&&Repeaters.offset(5_000_000L).equals("+5.000"),"offset text");

    // saving
    for (Repeaters.Repeater r : rs) {
      Repeaters.Repeater b=Repeaters.fromLine(Repeaters.toLine(r));
      check(b!=null&&b.id.equals(r.id)&&b.notes.equals(r.notes)&&b.outHz==r.outHz&&b.offsetHz==r.offsetHz&&b.toneTx.equals(r.toneTx)
          &&Math.abs(b.lat-r.lat)<1e-6&&b.status==r.status&&b.region.equals("OK"),"round trip "+r.id);
    }
    check(Repeaters.fromLine("junk")==null,"bad line");

    // CSV import (club list style), including a row with no position and one with no frequency
    String csv="﻿Output,Input,CTCSS,Callsign,Location,Latitude,Longitude,Notes\n"
      +"146.820,146.220,151.4,W5MEL,\"NW 38th Terr, OKC\",35.5146,-97.5125,OCAPA open\n"
      +"147.210,,141.3,W5MEL,SE 66th & I-35,35.3967,-97.4870,\"says \"\"hi\"\"\"\n"
      +"443.275,448.275,,W5MEL,OKC,,,no position\n"
      +",,,,,35,-97,no freq\n";
    Repeaters.ImportResult ir=Repeaters.parseCsv(csv,"ocapa.csv");
    check(ir.repeaters.size()==2&&ir.skippedNoLocation==1&&ir.skippedNoFrequency==1,"csv counts "+ir.repeaters.size()+" "+ir.skippedNoLocation+" "+ir.skippedNoFrequency);
    Repeaters.Repeater m=ir.repeaters.get(0);
    check(m.outHz==146_820_000L&&m.offsetHz==-600_000L&&m.toneTx.equals("151.4")&&m.toneRx.equals("151.4")&&m.city.equals("NW 38th Terr, OKC"),"csv row 1 "+m.offsetHz+" "+m.city);
    Repeaters.Repeater m2=ir.repeaters.get(1);
    check(m2.offsetHz==600_000L&&m2.notes.equals("says \"hi\""),"csv default offset/quotes "+m2.offsetHz+" "+m2.notes);
    // CHIRP-style columns with duplex sign
    String chirp="Location,Name,Frequency,Duplex,Offset,Tone,rToneFreq,cToneFreq,Latitude,Longitude\n1,W5PAA,146.760000,-,0.600000,Tone,141.3,141.3,35.47,-97.52\n";
    Repeaters.ImportResult cr=Repeaters.parseCsv(chirp,"chirp.csv");
    check(cr.repeaters.size()==1,"chirp rows "+cr.repeaters.size());
    if (!cr.repeaters.isEmpty()) {
      Repeaters.Repeater c=cr.repeaters.get(0);
      check(c.outHz==146_760_000L&&c.offsetHz==-600_000L&&c.toneTx.equals("141.3"),"chirp row "+c.offsetHz+" "+c.toneTx);
    }

    System.out.println(fails==0?"RepeaterTest OK":"RepeaterTest: "+fails+" FAIL");
  }
}
