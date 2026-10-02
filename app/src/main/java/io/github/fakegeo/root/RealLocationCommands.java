package io.github.fakegeo.root;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;

/** Fixed root-private paths. Never change the virtual profile to acquire the user's real position. */
public final class RealLocationCommands {
    public static final String REQUEST=RootCommands.DIRECTORY+"/real-gps-request.json";
    public static final String RESULT=RootCommands.DIRECTORY+"/real-gps-result.json";
    public static String begin(String token,long elapsed,long wall) throws Exception {
        validate(token);
        if(elapsed<=0 || wall<=0) throw new IllegalArgumentException("Invalid location request time");
        String json=new JSONObject().put("token",token).put("elapsed",elapsed).put("wall",wall).put("duration",40000).toString();
        String encoded=Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return RootCommands.prepareDirectory()+safePaths()
                +"tmp=\"$d/real-gps-request.new.$$\"\ntrap 'rm -f \"$tmp\"' EXIT\n"
                +"printf '%s' '"+encoded+"' | base64 -d > \"$tmp\"\n"
                +"chown 1000:1000 \"$tmp\"\nchmod 600 \"$tmp\"\nchcon u:object_r:system_data_file:s0 \"$tmp\"\n"
                +"mv -f \"$tmp\" '"+REQUEST+"'\necho REAL_GPS_REQUESTED\n";
    }
    public static String read(String token) {
        validate(token);
        return "set -eu\n[ \"$(id -u)\" = 0 ] || exit 1\n"+safePaths()
                +"if [ -f '"+RESULT+"' ] && grep -Fq '\"token\":\""+token+"\"' '"+RESULT+"'; then cat '"+RESULT+"'; else echo REAL_GPS_PENDING; fi\n";
    }
    public static String cancel(String token) {
        validate(token);
        return "set -eu\n[ \"$(id -u)\" = 0 ] || exit 1\n"+safePaths()
                +"for f in '"+REQUEST+"' '"+RESULT+"'; do if [ -f \"$f\" ] && grep -Fq '\"token\":\""+token+"\"' \"$f\"; then rm -f \"$f\"; fi; done\n";
    }
    private static String safePaths() { return "[ ! -L '"+RootCommands.DIRECTORY+"' ] && [ ! -L '"+REQUEST+"' ] && [ ! -L '"+RESULT+"' ] || exit 1\n"; }
    private static void validate(String token) { if(token==null || !token.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid location token"); }
    private RealLocationCommands() {}
}
