package cn.yilan.news;

/** UI-only color tokens. No Android dependencies so contrast and preference fallback can be tested. */
public final class ReaderTheme {
  public final boolean dark;
  public final int background,surface,raised,ink,muted,accent,onAccent,soft,line,warm,glow,danger;
  private ReaderTheme(boolean dark,int background,int surface,int raised,int ink,int muted,int accent,int onAccent,int soft,int line,int warm,int glow,int danger){
    this.dark=dark;this.background=background;this.surface=surface;this.raised=raised;this.ink=ink;this.muted=muted;this.accent=accent;this.onAccent=onAccent;this.soft=soft;this.line=line;this.warm=warm;this.glow=glow;this.danger=danger;
  }
  public static String mode(String value){return "night".equals(value)?"night":"day";}
  public static String scene(int page){switch(page){case 0:return "scene_news";case 1:return "scene_ai";case 2:return "scene_saved";default:return "";}}
  public int scrimTop(){return dark?0xa3071327:0x9cf5f8ff;}
  public int scrimBottom(){return dark?0xbc050c18:0xb3f8fbff;}
  public int glassTop(boolean selected){return selected?(0xe8000000|(accent&0xffffff)):(dark?0xd2142236:0xe8ffffff);}
  public int glassBottom(boolean selected){return selected?(0xef000000|(accent&0xffffff)):(dark?0xd91a2940:0xe0f5faff);}
  public int glassEdge(){return dark?0xb58dcfff:0x9274b0cf;}
  public static ReaderTheme forMode(String value){
    if("night".equals(mode(value)))return new ReaderTheme(true,0xff0b111b,0xff131e2b,0xff1a2735,0xffedf0f4,0xff9faebb,0xff87d4cc,0xff0c2228,0xff203a43,0xff2a3947,0xffedbd9d,0xff241f2b,0xffffada4);
    return new ReaderTheme(false,0xfff6f8fc,0xffffffff,0xfff8faff,0xff172e43,0xff536778,0xff155e85,0xffffffff,0xffe6eff9,0xffdce5ef,0xff985631,0xffe9f3ff,0xff9f3c43);
  }
}
