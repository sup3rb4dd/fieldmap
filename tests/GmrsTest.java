import app.fieldmap.core.*;
import java.util.*;
public class GmrsTest {
  static int fails=0;
  static void check(boolean ok,String msg){ if(!ok){System.out.println("FAIL "+msg);fails++;} }
  public static void main(String[] a) throws Exception {
    // callsigns
    check(Gmrs.validCall("WRXX123")&&Gmrs.validCall("KAA1234")&&!Gmrs.validCall("KF0ABC")&&!Gmrs.validCall("")&&!Gmrs.validCall("WRXX12"),"validCall");
    check(Gmrs.cleanCall(" wrxx-123 ").equals("WRXX123"),"cleanCall");
    check(Gmrs.cleanUnit("eli jah!!").equals("ELIJAH"),"cleanUnit "+Gmrs.cleanUnit("eli jah!!"));
    for (String c : new String[]{"WRXX123","WRXX120","WRXX129","KAA1234","KAA1230"}) {
      String d=Gmrs.destFor(c);
      // through a real packet and back
      Ax25 f=Ax25.ui("ELI", d, new ArrayList<String>(), "=3531.84N/09739.26W[");
      Ax25 g=Ax25.decode(f.encode());
      check(g!=null&&Gmrs.callFrom(g.dest).equals(c),"callsign survives a packet: "+c+" -> "+d+" -> "+(g==null?null:g.dest));
      check(g!=null&&g.source.equals("ELI")&&g.path.isEmpty(),"source/path "+(g==null?null:g.source));
    }
    check(Gmrs.callFrom("APZFMP").equals("APZFMP"),"plain dest");
    // sizes: every kind of burst stays inside the limit
    double lat=35.5306, lon=-97.6544;
    String pos=Aprs.position(lat,lon,'/','[',"");
    check(pos.length()<=Gmrs.MAX_INFO,"pos len "+pos.length());
    String obj=Aprs.object("RALLY PT",lat,lon,'\\','A',"x".repeat(Gmrs.MAX_INFO-37),true);
    check(obj.length()<=Gmrs.MAX_INFO,"obj len "+obj.length());
    String msg=Aprs.message("FIELDMAP","y".repeat(Gmrs.MAX_TEXT),null);
    check(msg.length()==Gmrs.MAX_INFO,"msg len "+msg.length());
    String sos=Tac.sos("ELIJAH",lat,lon);
    check(sos.length()<=Gmrs.MAX_TEXT,"sos fits gmrs "+sos.length());
    String chk=Tac.checkIn("OK",lat,lon);
    check(chk.length()<=Gmrs.MAX_TEXT,"checkin fits gmrs "+chk.length());
    check(Gmrs.airtime(Gmrs.MAX_INFO,300)<1.0,"airtime "+Gmrs.airtime(Gmrs.MAX_INFO,300));
    System.out.println("longest burst: "+String.format("%.2f",Gmrs.airtime(Gmrs.MAX_INFO,300))+" s with 300 ms key-up");
    // shapes thinned to one burst
    List<double[]> pts=new ArrayList<>();
    for(int i=0;i<40;i++) pts.add(new double[]{lat+i*0.001+Math.sin(i)*0.0004, lon+i*0.0012});
    String sh=Shapes.share("Route to camp",false,pts,Gmrs.MAX_TEXT);
    check(sh!=null&&sh.length()<=Gmrs.MAX_TEXT,"shape fits "+(sh==null?-1:sh.length()));
    Shapes.Shared back=Shapes.parseShared(sh);
    check(back!=null&&back.pts.size()>=2&&!back.closed,"shape parses, pts="+(back==null?0:back.pts.size()));
    System.out.println("40-point route -> "+(back==null?0:back.pts.size())+" points in "+(sh==null?0:sh.length())+" chars");
    List<double[]> sq=Arrays.asList(new double[]{lat,lon},new double[]{lat+0.01,lon},new double[]{lat+0.01,lon+0.01},new double[]{lat,lon+0.01});
    String zs=Shapes.share("HOME",true,sq,Gmrs.MAX_TEXT);
    Shapes.Shared zb=Shapes.parseShared(zs);
    check(zs!=null&&zb!=null&&zb.closed&&zb.pts.size()==4,"zone 4 corners "+zs);
    check(Shapes.share("x",false,pts).length()<=200,"default share unchanged");
    // queue: one per 30 s, newest position only, SOS first
    Gmrs.Queue q=new Gmrs.Queue();
    check(q.offer("msg","A",false)&&q.offer("pos","P1",false)&&q.offer("msg","B",false)&&q.offer("pos","P2",false),"offer");
    check(q.size()==3,"old position replaced, size="+q.size());
    check("A".equals(q.poll(0)),"first out at once");
    check(q.poll(29_000)==null,"nothing before 30 s");
    check(q.waitMs(10_000)==20_000,"wait "+q.waitMs(10_000));
    check(q.offer("sos","S",true),"sos accepted");
    check("S".equals(q.poll(30_000)),"sos jumps the line");
    check("B".equals(q.poll(60_000))&&"P2".equals(q.poll(90_000))&&q.poll(200_000)==null,"rest in order");
    check(!q.offer("msg","z".repeat(Gmrs.MAX_INFO+1),false),"too long refused");
    for(int i=0;i<Gmrs.MAX_QUEUE;i++) check(q.offer("msg","m"+i,false),"fill "+i);
    check(!q.offer("msg","over",false)&&q.offer("sos","S2",true),"full refuses normal, takes sos");
    // duplicate rule is in Store (Android), covered by reading; mesh receipt parsing:
    byte[] routing=new Proto.Writer().varint(1,Mesh.PORT_ROUTING).bytes(2,new byte[0]).fixed32(6,0x1234abcd).toBytes();
    byte[] mp=new Proto.Writer().fixed32(1,0x55,true).fixed32(2,0x66,true).message(4,new Proto.Writer().varint(1,Mesh.PORT_ROUTING).bytes(2,new byte[0]).fixed32(6,0x1234abcd)).toBytes();
    Mesh.Event e=Mesh.parseFromRadio(new Proto.Writer().bytes(2,mp).toBytes());
    check(e.kind==Mesh.Kind.ROUTING&&e.requestId==0x1234abcd&&e.routingError==0&&e.from==0x55,"routing ack "+e.kind+" "+Integer.toHexString(e.requestId));
    byte[] err=new Proto.Writer().varint(3,5).toBytes();
    byte[] mp2=new Proto.Writer().fixed32(1,0x55,true).message(4,new Proto.Writer().varint(1,Mesh.PORT_ROUTING).bytes(2,err).fixed32(6,7)).toBytes();
    Mesh.Event e2=Mesh.parseFromRadio(new Proto.Writer().bytes(2,mp2).toBytes());
    check(e2.kind==Mesh.Kind.ROUTING&&e2.routingError==5,"routing error "+e2.routingError);
    // import: entity anywhere in the file is refused
    String pad="<!-- "+"x".repeat(6000)+" -->";
    try { GeoImport.parse(("<?xml version=\"1.0\"?>"+pad+"<!DOCTYPE a [<!ENTITY b \"c\">]><gpx></gpx>").getBytes("UTF-8")); check(false,"late entity accepted"); }
    catch(java.io.IOException ok){ }
    System.out.println(fails==0?"ALL OK":fails+" FAILURES");
  }
}
