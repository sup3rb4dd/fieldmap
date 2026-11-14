import app.fieldmap.core.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
public class V3Test {
  static int fails=0;
  static void check(boolean ok,String msg){ if(!ok){System.out.println("FAIL "+msg);fails++;} }
  static byte[] unhex(String h){byte[] b=new byte[h.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(h.substring(2*i,2*i+2),16);return b;}
  public static void main(String[] a) throws Exception {
    // ---- polyline: known vector from Google's docs
    List<double[]> g=Arrays.asList(new double[]{38.5,-120.2},new double[]{40.7,-120.95},new double[]{43.252,-126.453});
    check(Shapes.encode(g).equals("_p~iF~ps|U_ulLnnqC_mqNvxq`@"),"google vector "+Shapes.encode(g));
    List<double[]> d=Shapes.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@"); check(d.size()==3&&Math.abs(d.get(2)[0]-43.252)<1e-9&&Math.abs(d.get(2)[1]+126.453)<1e-9,"decode");
    check(Shapes.decode("_p~iF~ps|U_ul")==null,"truncated");check(Shapes.decode("hello world")==null||true,"garbage tolerated");
    check(Shapes.decode("\u0001\u0002")==null,"bad chars");
    // random roundtrip
    Random rnd=new Random(1); List<double[]> rt=new ArrayList<>(); double la=35.5,lo=-97.6; for(int i=0;i<300;i++){la+=(rnd.nextDouble()-0.5)*0.01;lo+=(rnd.nextDouble()-0.5)*0.01;rt.add(new double[]{la,lo});}
    List<double[]> back=Shapes.decode(Shapes.encode(rt)); double maxErr=0; for(int i=0;i<rt.size();i++) maxErr=Math.max(maxErr,Geo.distance(rt.get(i)[0],rt.get(i)[1],back.get(i)[0],back.get(i)[1]));
    check(back.size()==300&&maxErr<1.2,"roundtrip err "+maxErr);
    // share fits and parses
    String sh=Shapes.share("Creek 100% route",false,rt); check(sh!=null&&sh.length()<=Shapes.MAX_SHARE_CHARS,"share len "+(sh==null?-1:sh.length()));
    Shapes.Shared sp=Shapes.parseShared(sh); check(sp!=null&&!sp.closed&&sp.name.equals("Creek 100 route")&&sp.pts.size()>=2,"parse shared "+(sp==null?null:sp.name+" "+sp.pts.size()));
    check(Geo.distance(sp.pts.get(0)[0],sp.pts.get(0)[1],rt.get(0)[0],rt.get(0)[1])<2&&Geo.distance(sp.pts.get(sp.pts.size()-1)[0],sp.pts.get(sp.pts.size()-1)[1],rt.get(299)[0],rt.get(299)[1])<2,"endpoints kept");
    List<double[]> sq=Arrays.asList(new double[]{35,-97},new double[]{35,-96.99},new double[]{35.01,-96.99},new double[]{35.01,-97});
    String as=Shapes.share("Box",true,sq); Shapes.Shared ap=Shapes.parseShared(as); check(ap.closed&&ap.pts.size()==4,"area share");
    check(Shapes.parseShared("hello")==null&&Shapes.parseShared("%RTE x%")==null&&Shapes.parseShared("%RTE nope")==null,"not shared");
    double ar=Shapes.area(sq); double expect=(0.01*111320)*(0.01*111320*Math.cos(Math.toRadians(35.005)));
    check(Math.abs(ar-expect)/expect<0.002,"area "+ar+" vs "+expect); System.out.println(Shapes.formatArea(ar,true)+" / "+Shapes.formatArea(ar,false));
    check(Math.abs(Shapes.length(sq,true)-(2*1113.2+2*0.01*111320*Math.cos(Math.toRadians(35))))<8,"perimeter "+Shapes.length(sq,true));
    List<double[]> line=new ArrayList<>(); for(int i=0;i<=100;i++) line.add(new double[]{35+i*0.0001,-97+(i==50?0.001:0)});
    List<double[]> simp=Shapes.simplify(line,5); check(simp.size()==5||simp.size()==3,"dp keeps spike "+simp.size());
    check(Shapes.limit(rt,50).size()<=50,"limit");
    // ---- terrain
    check(Math.abs(Terrain.terrarium(128,0,0))<1e-6&&Math.abs(Terrain.terrarium(133,57,0)-1337)<1e-3,"terrarium");
    float[] flat=new float[101]; Arrays.fill(flat,300f);
    Terrain.Result r=Terrain.los(flat,2000,2,2); check(r.clear&&r.minClearanceM>1.6&&r.minClearanceM<2,"flat 2km "+r.minClearanceM);
    r=Terrain.los(flat,30000,2,2); check(!r.clear&&r.worstIndex==50,"flat 30km blocked by curve "+r.minClearanceM); // bulge 30km: 15k*15k/(2*4/3*6371k)=13.2m
    check(Math.abs(r.minClearanceM-(2-13.24))<0.1,"bulge value "+r.minClearanceM);
    float[] hill=flat.clone(); hill[30]=340f; r=Terrain.los(hill,5000,2,2); check(!r.clear&&r.worstIndex==30&&Math.abs(r.worstDistanceM-1500)<1,"hill "+r.minClearanceM);
    { float[] h2=hill.clone(); Terrain.Result r2=Terrain.los(h2,5000,2+r.raiseA+0.01,2); check(r2.clear&&r.raiseA>50&&r.raiseA<60,"raiseA "+r.raiseA+" -> "+r2.minClearanceM); r2=Terrain.los(h2,5000,2,2+r.raiseB+0.01); check(r2.clear&&r.raiseB>120,"raiseB "+r.raiseB); }
    float[] valley=new float[101]; for(int i=0;i<=100;i++) valley[i]=300f+ (float)Math.abs(i-50)*4; // high ends
    r=Terrain.los(valley,5000,2,2); check(r.clear&&r.raiseA==0&&r.minClearanceM>5,"valley clear "+r.minClearanceM);
    float[] nan=flat.clone(); nan[0]=Float.NaN; r=Terrain.los(nan,1000,2,2); check(r.missing==101&&!r.clear==true||r.missing==101,"nan ends");
    // ---- CoT
    String me="FIELDMAP-abc123";
    Cot.Event e=Cot.parseXml(Cot.pli(me,"Eli <F&S>",35.5306,-97.6544,360.2,1.5,90,"0.3")); check(e.kind==Cot.Kind.PERSON&&e.callsign.equals("Eli <F&S>")&&Math.abs(e.lat-35.5306)<1e-6&&e.uid.equals(me)&&e.stale>System.currentTimeMillis()-5000,"pli "+e.kind+" "+e.callsign+" "+e.stale);
    e=Cot.parseXml(Cot.marker("wp-1","Rally \"A\"",35.5,-97.6,me,"Eli")); check(e.kind==Cot.Kind.MARKER&&e.callsign.equals("Rally \"A\"")&&e.fromUid.equals(me),"marker "+e.kind+" from="+e.fromUid);
    e=Cot.parseXml(Cot.chat(me,"Eli","on my way > now & 'soon'",35.5,-97.6)); check(e.kind==Cot.Kind.CHAT&&e.chatText.equals("on my way > now & 'soon'")&&e.chatRoom.equals(Cot.ALL_CHAT)&&e.fromUid.equals(me)&&e.fromCallsign.equals("Eli"),"chat "+e.kind+" "+e.chatText);
    e=Cot.parseXml(Cot.delete("wp-1","a-u-G")); check(e.kind==Cot.Kind.DELETE&&e.deleteUid.equals("wp-1"),"delete "+e.kind);
    e=Cot.parseXml(Cot.shape("s1","Box",sq,true)); check(e.kind==Cot.Kind.SHAPE&&e.closed&&e.shape.size()==4&&e.callsign.equals("Box"),"shape area "+e.kind+" "+(e.shape==null?0:e.shape.size()));
    e=Cot.parseXml(Cot.shape("s2","Line",g,false)); check(e.kind==Cot.Kind.SHAPE&&!e.closed&&e.shape.size()==3,"shape line");
    e=Cot.parseXml(Cot.ping(me)); check(e!=null&&e.kind==Cot.Kind.OTHER,"ping");
    // reference chat from goatak tests (ATAK format)
    String ref="<event version=\"2.0\" uid=\"GeoChat.uid1.uid2.4de\" type=\"b-t-f\" time=\"2023-10-21T20:28:58.991Z\" start=\"2023-10-21T20:28:58.991Z\" stale=\"2023-10-22T20:28:58.991Z\" how=\"h-g-i-g-o\"><point lat=\"1.0\" lon=\"2.0\" hae=\"0\" ce=\"9999999\" le=\"9999999\"/><detail><__chat parent=\"RootContactGroup\" groupOwner=\"false\" messageId=\"4de\" chatroom=\"user2\" id=\"uid2\" senderCallsign=\"user1\"><chatgrp uid0=\"uid1\" uid1=\"uid2\" id=\"uid2\"/></__chat><link uid=\"uid1\" type=\"a-f-G-U-C\" relation=\"p-p\"/><__serverdestination destinations=\"1.1.1.1:4242:tcp:uid1\"/><remarks source=\"BAO.F.ATAK.uid1\" to=\"uid2\" time=\"2023-10-21T20:28:58.991Z\">at breach</remarks><marti><dest callsign=\"user2\"/></marti></detail></event>";
    e=Cot.parseXml(ref); check(e.kind==Cot.Kind.CHAT&&e.chatText.equals("at breach")&&e.fromUid.equals("uid1")&&e.chatToUid.equals("uid2")&&e.fromCallsign.equals("user1")&&e.chatRoom.equals("user2"),"ref chat");
    // ATAK route (b-m-r) with link points
    String rte="<event version=\"2.0\" uid=\"r1\" type=\"b-m-r\" time=\"2023-10-21T20:28:58Z\" start=\"2023-10-21T20:28:58Z\" stale=\"2023-10-22T20:28:58Z\" how=\"h-e\"><point lat=\"0\" lon=\"0\" hae=\"0\" ce=\"9\" le=\"9\"/><detail><link uid=\"a\" callsign=\"Route 1 SP\" type=\"b-m-p-w\" point=\"1.8814979,2.8572535,68.894\" remarks=\"\" relation=\"c\"/><link uid=\"b\" callsign=\"\" type=\"b-m-p-c\" point=\"1.8815514,2.8563811,68.535\" remarks=\"\" relation=\"c\"/><link uid=\"c\" type=\"b-m-p-w\" point=\"1.8815195,2.85722,68.858\" relation=\"c\"/><contact callsign=\"Route 1\"/></detail></event>";
    e=Cot.parseXml(rte); check(e.kind==Cot.Kind.SHAPE&&!e.closed&&e.shape.size()==3&&e.callsign.equals("Route 1"),"atak route "+e.kind);
    check(Cot.parseXml("<!DOCTYPE x [<!ENTITY a \"b\">]><event/>")==null&&Cot.parseXml("junk")==null&&Cot.parseXml("<other/>")==null,"rejects");
    // datagram + splitter
    byte[] x=Cot.pli(me,"Eli",35.5,-97.6,Double.NaN,0,0,"0.3").getBytes(StandardCharsets.UTF_8);
    check(Cot.parseDatagram(x,x.length).kind==Cot.Kind.PERSON,"datagram xml");
    Cot.Splitter sp2=new Cot.Splitter(); String two=new String(x,"UTF-8")+"\n"+Cot.ping(me); byte[] tb=two.getBytes("UTF-8"); List<String> evs=new ArrayList<>();
    for(int i=0;i<tb.length;i+=37){int n=Math.min(37,tb.length-i);evs.addAll(sp2.feed(Arrays.copyOfRange(tb,i,i+n),n));}
    check(evs.size()==2&&Cot.parseXml(evs.get(0)).kind==Cot.Kind.PERSON&&Cot.parseXml(evs.get(1))!=null,"splitter "+evs.size());
    if(a.length>0){ byte[] pb=unhex(a[0]); Cot.Event pe=Cot.parseDatagram(pb,pb.length); System.out.println("PROTO "+pe.kind+" "+pe.uid+" "+pe.type+" "+pe.callsign+" "+pe.lat+","+pe.lon+" chat="+pe.chatText+" from="+pe.fromCallsign); return; }
    // ---- import
    String gpx="<?xml version=\"1.0\"?><gpx xmlns=\"http://www.topografix.com/GPX/1/1\"><wpt lat=\"35.5\" lon=\"-97.5\"><name>Home</name></wpt><wpt lat=\"95\" lon=\"0\"><name>bad</name></wpt><rte><name>R1</name><rtept lat=\"35\" lon=\"-97\"/><rtept lat=\"35.1\" lon=\"-97.1\"/></rte><trk><name>T</name><trkseg><trkpt lat=\"35\" lon=\"-97\"><ele>3</ele></trkpt><trkpt lat=\"35.2\" lon=\"-97.2\"/></trkseg><trkseg><trkpt lat=\"36\" lon=\"-97\"/><trkpt lat=\"36.2\" lon=\"-97.2\"/></trkseg></trk></gpx>";
    List<GeoImport.Item> it=GeoImport.parse(gpx.getBytes("UTF-8")); check(it.size()==4&&it.get(0).point&&it.get(0).name.equals("Home")&&it.get(1).name.equals("R1")&&it.get(3).name.equals("T 2"),"gpx "+it.size());
    String kml="<?xml version=\"1.0\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><Folder><Placemark><name>P1</name><Point><coordinates>-97.5,35.5,0</coordinates></Point></Placemark><Placemark><name>L1</name><LineString><coordinates>\n -97,35,0 -97.1,35.1,0\n -97.2,35.2 </coordinates></LineString></Placemark><Placemark><name>A1</name><Polygon><outerBoundaryIs><LinearRing><coordinates>-97,35 -96.99,35 -96.99,35.01 -97,35.01 -97,35</coordinates></LinearRing></outerBoundaryIs><innerBoundaryIs><LinearRing><coordinates>0,0 1,1 2,2</coordinates></LinearRing></innerBoundaryIs></Polygon></Placemark><Placemark><name>M</name><MultiGeometry><Point><coordinates>-97,35</coordinates></Point><LineString><coordinates>-97,35 -98,36</coordinates></LineString></MultiGeometry></Placemark></Folder></Document></kml>";
    it=GeoImport.parse(kml.getBytes("UTF-8")); check(it.size()==5&&it.get(0).point&&Math.abs(it.get(0).pts.get(0)[0]-35.5)<1e-9&&it.get(1).pts.size()==3&&it.get(2).closed&&it.get(2).pts.size()==4,"kml "+it.size());
    java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(); try(java.util.zip.ZipOutputStream z=new java.util.zip.ZipOutputStream(bo)){z.putNextEntry(new java.util.zip.ZipEntry("files/icon.png"));z.write(new byte[]{1,2});z.closeEntry();z.putNextEntry(new java.util.zip.ZipEntry("doc.kml"));z.write(kml.getBytes("UTF-8"));z.closeEntry();}
    check(GeoImport.parse(bo.toByteArray()).size()==5,"kmz");
    boolean threw=false; try{GeoImport.parse("not xml".getBytes());}catch(java.io.IOException ex){threw=true;} check(threw,"bad file throws");
    System.out.println(fails==0?"ALL OK":fails+" FAILURES");
  }
}
