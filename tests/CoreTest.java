import app.fieldmap.core.*;
import java.util.*;
public class CoreTest {
  static int fails=0;
  static void check(boolean ok,String msg){ System.out.println((ok?"PASS ":"FAIL ")+msg); if(!ok)fails++; }
  public static void main(String[] a){
    String m = Mgrs.format(38.889484,-77.035278);
    check(m.equals("18S UJ 23479 06481"), "wash mon "+m);
    double[][] pts={{35.53,-97.65},{-33.8568,151.2153},{51.5007,-0.1246},{64.1466,-21.9426},{-54.8,-68.3},{0.0001,0.0001},{60.0,5.0},{78.2,15.6},{35.0,-120.0}};
    for(double[] p:pts){ String s=Mgrs.format(p[0],p[1]); double[] b=Mgrs.parse(s); double d=Geo.distance(p[0],p[1],b[0],b[1]); check(d<2.0,"roundtrip "+s+" err "+String.format("%.2f",d)); }
    check(Mgrs.parse("garbage")==null,"bad parse null");
    double[] q=Mgrs.parse("18SUJ2348706483"); check(Math.abs(q[0]-38.88948)<0.0001,"parse compact "+q[0]+","+q[1]);
    check(Mgrs.format(35.53,-97.65,3).length()>0,"3-digit "+Mgrs.format(35.53,-97.65,3));
    System.out.println(Geo.rangeBearing(35.5,-97.6,35.6,-97.5,true));
    // AX25+KISS roundtrip
    Ax25 f=Ax25.ui("KF0ABC-7","APZFMP",Arrays.asList("WIDE1-1","WIDE2-1"),Aprs.position(35.53,-97.65,'/','[',"FieldMap"));
    byte[] k=Kiss.encode(f.encode());
    Kiss.Decoder dec=new Kiss.Decoder();
    List<byte[]> fr=new ArrayList<>(); fr.addAll(dec.feed(Arrays.copyOfRange(k,0,10),10)); byte[] rest=Arrays.copyOfRange(k,10,k.length); fr.addAll(dec.feed(rest,rest.length));
    check(fr.size()==1,"kiss frames "+fr.size());
    Ax25 g=Ax25.decode(fr.get(0)); System.out.println(g);
    check(g.source.equals("KF0ABC-7")&&g.dest.equals("APZFMP")&&g.path.size()==2,"ax25 addrs");
    Aprs.Packet p=Aprs.decode(g); check(p.type==Aprs.Type.POSITION&&Math.abs(p.lat-35.53)<0.001&&Math.abs(p.lon+97.65)<0.001,"own position "+p.lat+","+p.lon+" "+p.comment);
    p=Aprs.decode(Ax25.ui("N0CALL","APRS",List.of(),"!4903.50N/07201.75W-Test 001234")); check(Math.abs(p.lat-49.0583)<0.001&&Math.abs(p.lon+72.0292)<0.001&&p.symCode=='-',"uncompressed");
    p=Aprs.decode(Ax25.ui("N0CALL","APRS",List.of(),"=/5L!!<*e7>7P[")); check(Math.abs(p.lat-49.5)<0.01&&Math.abs(p.lon+72.75)<0.01,"compressed "+p.lat+","+p.lon);
    p=Aprs.decode(Ax25.ui("N0CALL","S32UPT",List.of(),"`(_fn\"Oj/")); check(Math.abs(p.lat-(33+25.04/60))<0.001&&Math.abs(p.lon+112.129)<0.001,"mic-e "+p.lat+","+p.lon);
    p=Aprs.decode(Ax25.ui("N0CALL","APRS",List.of(),"@092345z4903.50N/07201.75W>")); check(p.hasPosition(),"timestamped");
    String obj=Aprs.object("Rally Pt",35.5,-97.6,'\\','A',"meet here",true); System.out.println(obj);
    p=Aprs.decode(Ax25.ui("N0CALL","APRS",List.of(),obj)); check(p.type==Aprs.Type.OBJECT&&p.name.equals("Rally Pt")&&!p.killed&&Math.abs(p.lat-35.5)<0.001,"object");
    p=Aprs.decode(Ax25.ui("N0CALL","APRS",List.of(),Aprs.object("Rally Pt",35.5,-97.6,'\\','A',"",false))); check(p.killed,"killed object");
    p=Aprs.decode(Ax25.ui("N0CALL","APRS",List.of(),")AID #2!4903.50N/07201.75WA")); check(p.type==Aprs.Type.OBJECT&&p.name.equals("AID #2"),"item "+p.name);
    p=Aprs.decode(Ax25.ui("N0CALL","APRS",List.of(),Aprs.message("fieldmap","hello team","12"))); check(p.type==Aprs.Type.MESSAGE&&p.msgTo.equals("FIELDMAP")&&p.msgText.equals("hello team")&&"12".equals(p.msgId),"message");
    p=Aprs.decode(Ax25.ui("N0CALL","APRS",List.of(),Aprs.ack("KF0ABC-7","12"))); check(p.isAck&&"12".equals(p.msgId),"ack");
    // escaping
    byte[] raw=new byte[]{(byte)0xC0,(byte)0xDB,1}; Kiss.Decoder d2=new Kiss.Decoder(); byte[] enc=Kiss.encode(raw); List<byte[]> o=d2.feed(enc,enc.length); check(o.size()==1&&Arrays.equals(o.get(0),raw),"kiss escape");
    System.out.println(fails==0?"ALL OK":fails+" FAILURES");
  }
}
