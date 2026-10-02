package io.github.fakegeo.hook;

/** Conventional local WGS84 to GCJ-02 approximation; outside its bounds keep WGS84. */
public final class Gcj02 {
    private Gcj02() {}
    public static boolean applies(double lat, double lon) {
        return lon >= 72.004 && lon <= 137.8347 && lat >= 0.8293 && lat <= 55.8271;
    }
    public static double[] fromWgs84(double lat, double lon) {
        if (!applies(lat, lon)) return new double[]{lat, lon};
        double x = lon - 105, y = lat - 35;
        double dLat = -100 + 2*x + 3*y + 0.2*y*y + 0.1*x*y + 0.2*Math.sqrt(Math.abs(x));
        double dLon = 300 + x + 2*y + 0.1*x*x + 0.1*x*y + 0.1*Math.sqrt(Math.abs(x));
        double wave = (20*Math.sin(6*x*Math.PI) + 20*Math.sin(2*x*Math.PI))*2/3;
        dLat += wave + (20*Math.sin(y*Math.PI) + 40*Math.sin(y*Math.PI/3))*2/3
                + (160*Math.sin(y*Math.PI/12) + 320*Math.sin(y*Math.PI/30))*2/3;
        dLon += wave + (20*Math.sin(x*Math.PI) + 40*Math.sin(x*Math.PI/3))*2/3
                + (150*Math.sin(x*Math.PI/12) + 300*Math.sin(x*Math.PI/30))*2/3;
        double rad = lat*Math.PI/180, sin = Math.sin(rad);
        double magic = 1 - 0.00669342162296594323*sin*sin, root = Math.sqrt(magic);
        dLat = dLat*180/((6378245*(1-0.00669342162296594323))/(magic*root)*Math.PI);
        dLon = dLon*180/(6378245/root*Math.cos(rad)*Math.PI);
        return new double[]{lat+dLat, lon+dLon};
    }
    /** Invert the local approximation so map picks are published in system GPS coordinates. */
    public static double[] toWgs84(double lat,double lon) {
        if(!applies(lat,lon)) return new double[]{lat,lon};
        double wLat=lat,wLon=lon;
        for(int i=0;i<10;i++) {
            double[] mapped=fromWgs84(wLat,wLon);
            double dLat=mapped[0]-lat,dLon=mapped[1]-lon;
            wLat-=dLat; wLon-=dLon;
            if(Math.abs(dLat)<1e-9 && Math.abs(dLon)<1e-9) break;
        }
        return new double[]{wLat,wLon};
    }
}
