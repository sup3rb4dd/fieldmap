import app.fieldmap.core.*;
public class MeshTest {
  static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}
  static byte[] unhex(String h){byte[] b=new byte[h.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(h.substring(2*i,2*i+2),16);return b;}
  public static void main(String[] a){
    if(a.length==0){
      System.out.println("WANT "+hex(Mesh.wantConfig(0xDEADBEEF)));
      System.out.println("TEXT "+hex(Mesh.text(Mesh.BROADCAST,0,"hello mesh ✓",123456)));
      System.out.println("POS "+hex(Mesh.packet(Mesh.BROADCAST,0,Mesh.PORT_POSITION,Mesh.positionPayload(35.53,-97.65,-12,1700000000),77,false)));
      System.out.println("WP "+hex(Mesh.packet(Mesh.BROADCAST,1,Mesh.PORT_WAYPOINT,Mesh.waypointPayload(0x9abcdef0,-33.85,151.2,1,"Rally","meet"),78,true)));
    } else {
      Mesh.Event e=Mesh.parseFromRadio(unhex(a[0]));
      System.out.println(e.kind+" my="+e.myNode+" from="+Integer.toHexString(e.from)+" to="+Integer.toHexString(e.to)+" ch="+e.channel+" port="+e.port+" text="+e.text+" lat="+e.lat+" lon="+e.lon
        +(e.node!=null?" node="+e.node.label()+"/"+e.node.id+"/"+e.node.shortName+" "+e.node.lat+","+e.node.lon+" heard="+e.node.lastHeard:"")
        +(e.waypoint!=null?" wp="+Integer.toHexString(e.waypoint.id)+" "+e.waypoint.name+" "+e.waypoint.lat+","+e.waypoint.lon+" exp="+e.waypoint.expire:"")+" cfg="+e.configId);
    }
  }
}
