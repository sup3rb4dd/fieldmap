import app.fieldmap.core.*;
import java.io.File;
import java.util.*;
public class TacTest {
  static int fails=0;
  static void check(boolean ok,String msg){ if(!ok){System.out.println("FAIL "+msg);fails++;} }
  public static void main(String[] a) throws Exception {
    double lat=35.5306, lon=-97.6544;
    String sos=Tac.sos("Elijah Heltec", lat, lon); System.out.println(sos+" ("+sos.length()+")");
    check(sos.length()<=67,"sos fits aprs");
    check(Tac.isSos(sos)&&!Tac.isSosCancel(sos),"isSos");
    double[] g=Tac.findGrid(sos); check(g!=null&&Geo.distance(lat,lon,g[0],g[1])<2,"sos grid");
    String c=Tac.sosCancel("Elijah"); check(Tac.isSosCancel(c)&&!Tac.isSos(c),"cancel "+c);
    check(Tac.isSos("  *sos* help")&&!Tac.isSos("sos lol")&&!Tac.isSos("no *SOS* here"),"sos variants");
    check(Tac.sos("x",Double.NaN,Double.NaN).equals("*SOS* X NO GPS FIX"),"sos nofix "+Tac.sos("x",Double.NaN,Double.NaN));
    String chk=Tac.checkIn("ok",lat,lon); System.out.println(chk); check(Tac.isCheckIn(chk)&&Tac.findGrid(chk)!=null,"checkin");
    check(Tac.findGrid("meet at 14spe2168836396 ok")!=null,"compact lower");
    check(Tac.findGrid("rally 14S PE 216 363 now")!=null,"6 digit");
    g=Tac.findGrid("14S PE 2199 3271"); check(g!=null&&Geo.distance(lat,lon,g[0],g[1])<20,"8 digit");
    for(String no: new String[]{"call me at 4055551234","see you at 5pm","I have 14 apples","arriving 12AB 34 trucks? no","room 14SPE","abc14SPE2168836396","14SPE21688363961"})
      check(Tac.findGrid(no)==null,"no grid in: "+no);
    check(Tac.findGrid(null)==null,"null");
    check(Tac.withGrid("OK",Double.NaN,0).equals("OK"),"withGrid nofix");
    // geo
    check(Math.abs(Geo.turn(10,350)-20)<1e-9&&Math.abs(Geo.turn(350,10)+20)<1e-9&&Math.abs(Geo.turn(180,0))==180,"turn");
    check(Geo.formatEta(1000,1).equals("16 MIN")||Geo.formatEta(1000,1).equals("17 MIN"),"eta "+Geo.formatEta(1000,1));
    check(Geo.formatEta(10,1).equals("<1 MIN")&&Geo.formatEta(100,0).equals("--")&&Geo.formatEta(7500,1).equals("2H 05M"),"eta edges "+Geo.formatEta(7500,1));
    System.out.println(Geo.formatSpeed(1.4,true)+" / "+Geo.formatSpeed(1.4,false));
    // trail
    File f=File.createTempFile("trail",".csv"); f.delete();
    Trail t=new Trail(f);
    check(t.add(35.0,-97.0,300,1000,5),"first"); check(!t.add(35.00001,-97.0,300,2000,5),"too close");
    check(!t.add(35.01,-97.0,300,3000,80),"bad acc"); check(t.add(35.001,-97.0,Double.NaN,4000,5),"second");
    check(!t.add(35.0012,-97.0,300,5000,40),"within gps error");
    Trail t2=new Trail(f); t2.load(); check(t2.size()==2&&Double.isNaN(t2.get(1)[3])&&t2.get(0)[3]==300&&(long)t2.get(1)[2]==4000,"reload "+t2.size());
    check(Math.abs(t2.length()-111.2)<1,"length "+t2.length());
    for(int i=0;i<Trail.MAX_POINTS+10;i++) t.add(36+i*0.001,-97,0,i,5);
    check(t.size()<=Trail.MAX_POINTS,"cap "+t.size()); Trail t3=new Trail(f); t3.load(); check(t3.size()==t.size(),"cap persisted "+t3.size()+" vs "+t.size());
    t.clear(); check(t.size()==0&&!f.exists(),"clear");
    // gpx
    String x=Gpx.build("My <track> & co", Arrays.asList(new double[]{35,-97,1700000000000L,300.5}, new double[]{35.1,-97.1,0,Double.NaN}), Arrays.asList("Home \"A\""), Arrays.asList(new double[]{35.5,-97.5}));
    System.out.println(x);
    javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new java.io.ByteArrayInputStream(x.getBytes("UTF-8")));
    check(x.contains("<time>2023-11-14T22:13:20Z</time>")&&x.contains("<ele>300.5</ele>"),"gpx fields");
    System.out.println(fails==0?"ALL OK":fails+" FAILURES");
  }
}
