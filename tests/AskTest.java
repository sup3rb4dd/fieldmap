import app.fieldmap.core.*;
import java.util.*;
public class AskTest {
  static int fails=0;
  static void check(boolean ok,String msg){ if(!ok){System.out.println("FAIL "+msg);fails++;} }
  public static void main(String[] a) {
    // These are real replies from Lantern Relay (see lantern/RELAY.md).
    Ask.Reply r=Ask.parse("🔎 water\n1 Water purification [Wiki]\n2 Emergency water treatment [Water]\n3 Water filter [Wiki]");
    check(r.kind==Ask.Kind.RESULTS&&r.query.equals("water")&&r.results.size()==3,"results "+r.kind+" "+r.results.size());
    check(r.results.get(1).num==2&&r.results.get(1).title.equals("Emergency water treatment")&&r.results.get(1).source.equals("Water"),"result 2");
    r=Ask.parse("🔎 zzqx\nNo matches. Try fewer or different words.");
    check(r.kind==Ask.Kind.RESULTS&&r.noMatches&&r.results.isEmpty(),"no matches");
    r=Ask.parse("🔎 water\n4 Water [Wiki] cycle [Book]");
    check(r.results.size()==1&&r.results.get(0).title.equals("Water [Wiki] cycle")&&r.results.get(0).source.equals("Book"),"brackets in title");

    Ask.Article art=new Ask.Article("Water purif…","Wiki");
    r=Ask.parse("[1/3] Water purification [Wiki]\nWater purification is the process of removing undesirable chemicals.");
    check(r.kind==Ask.Kind.PART&&r.part==1&&r.total==3&&r.heading.equals("Water purification [Wiki]"),"part 1 "+r.heading);
    art.add(r,0);
    check(art.title.equals("Water purification"),"full title from heading: "+art.title);
    art.add(Ask.parse("[3/3] industrial applications."),-1);
    check(art.text().contains("chemicals. [… missing …] industrial"),"gap marked: "+art.text());
    check(art.currentIncomplete(),"incomplete");
    Ask.Article early=new Ask.Article("T","Wiki"); early.add(Ask.parse("[1/12] T [Wiki]\nFirst part."),0);
    check(Ask.Article.sectionText(early.secs.get(0)).equals("First part.")&&Ask.Article.notYetSent(early.secs.get(0))==11,"unsent parts aren't shown as missing: "+early.text());
    art.add(Ask.parse("[2/3] The goal is water fit for purpose."),-1);
    check(!art.currentIncomplete()&&art.text().equals("Water purification is the process of removing undesirable chemicals. The goal is water fit for purpose. industrial applications."),"whole summary: "+art.text());

    r=Ask.parse("☰ 1/1\n0 Summary\n1 History\n2 Boiling");
    check(r.kind==Ask.Kind.SECTIONS&&r.sections.size()==3&&r.sections.get(2).title.equals("Boiling"),"toc");
    art.addSections(r);
    // ?go 2
    art.add(Ask.parse("[1/2] § Boiling\nBring water to a rolling boil."),2);
    art.add(Ask.parse("[2/2] Let it cool."),-1);
    check(art.current==2&&art.secs.get(2).complete()&&art.secs.get(2).title.equals("Boiling"),"go 2");
    // ?more after Boiling, with no ?toc: the gateway moved on to a section we haven't seen
    Ask.Article b=new Ask.Article("X","Wiki");
    b.add(Ask.parse("[1/1] X [Wiki]\nIntro."),0);
    b.add(Ask.parse("[1/1] § History\nOld times."),-1);
    check(b.current==1&&b.sectionTitle(1).equals("History"),"next section by ?more");
    check(b.text().equals("Intro.\n\n§ HISTORY\nOld times."),"text "+b.text());

    r=Ask.parse("Lantern: search first: ?s words");
    check(r.kind==Ask.Kind.NOTICE&&r.text.equals("search first: ?s words"),"notice");
    check(Ask.parse("Lantern Relay: ?s words = search.").kind==Ask.Kind.NOTICE,"help is a notice");
    check(Ask.parse("hey are you there").kind==Ask.Kind.OTHER,"other");
    check(Ask.looksLikeGateway("Lantern Relay 🤖")&&Ask.looksLikeGateway("Base 🤖")&&!Ask.looksLikeGateway("Elijah"),"gateway names");
    check(Ask.search("  water filter ").equals("?s water filter")&&Ask.go(3).equals("?go 3")&&Ask.open(2).equals("?2"),"commands");
    // Lantern 1.6.4+ numbers section headings, and PDF books open on a page
    Ask.Article pdf=new Ask.Article("Emergency Water Treatment","Water");
    pdf.add(Ask.parse("[1/2] \u00a73 p.3 Disinfecting with bleach\nAdd 8 drops of bleach per gallon."),0);
    check(pdf.secs.containsKey(3)&&!pdf.secs.containsKey(0)&&pdf.sectionTitle(3).equals("p.3 Disinfecting with bleach")&&pdf.current==3,"numbered heading wins over expected section: "+pdf.secs.keySet());
    pdf.add(Ask.parse("[2/2] Stir and wait 30 minutes."),-1);
    pdf.add(Ask.parse("[1/1] \u00a74 p.4 Storage\nOne gallon per person per day."),-1);
    check(pdf.current==4&&pdf.text().contains("\u00a7 P.3 DISINFECTING WITH BLEACH\nAdd 8 drops of bleach per gallon. Stir and wait 30 minutes.")&&pdf.text().contains("\u00a7 P.4 STORAGE"),"pdf pages in order: "+pdf.text());
    Ask.Article wiki=new Ask.Article("W","Wiki"); wiki.add(Ask.parse("[1/1] \u00a72 Boiling\nBoil it."),-1);
    check(wiki.secs.containsKey(2)&&wiki.sectionTitle(2).equals("Boiling"),"numbered wiki section");
    System.out.println(fails==0?"AskTest OK":"AskTest "+fails+" failures");
  }
}
