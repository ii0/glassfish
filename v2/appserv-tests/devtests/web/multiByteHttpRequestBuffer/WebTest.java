import java.io.*;
import java.net.*;
import com.sun.ejte.ccl.reporter.*;

/**
 * Unit test for:
 *
 *  https://issues.apache.org/bugzilla/show_bug.cgi?id=44494
 *  ("Requests greater than 8k being truncated.")
 */
public class WebTest {

    private static SimpleReporterAdapter stat
        = new SimpleReporterAdapter("appserv-tests");

    private static final String TEST_NAME
        = "multi-byte-http-request-buffer";

    //private static char jp[] = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよわゐゑをん"
    private static char jp[] = "\u3068\u4eba\u6587"
            .toCharArray();

    private static char ascii[] = "abcdefghijklmnopqrstuvwxyz".toCharArray();

    private static String EXPECTED = "isSame:true<BR>";
    private static final String JSESSIONID = "JSESSIONID";
    private static String formName = "n";

    private String host;
    private String port;
    private String contextRoot;
    private int size;
    private boolean isAscii;

    private String jsessionId;

    public WebTest(String[] args) {
        host = args[0];
        port = args[1];
        contextRoot = args[2];
        size = Integer.parseInt(args[3]);
        isAscii = Boolean.valueOf(args[4]);
    }
    
    public static void main(String[] args) {
        stat.addDescription("Unit test for Multi-byte Http request buffer");
        WebTest webTest = new WebTest(args);
        webTest.doTest();
        stat.printSummary(TEST_NAME);
    }

    public void doTest() {
        try {
            invokeSetup();

            String[] uris = new String[] {
                    "/readLine.jsp", "/read.jsp", "/readCharB.jsp" };
            boolean status = true;
            for (String uri : uris) {
                boolean temp = invoke(uri);
                if (!temp) {
                    System.out.println("Unexpected results for " + uri);
                }
                status = status && temp;
            }

            if (status) {
                stat.addStatus(TEST_NAME, stat.PASS);
            } else {
                System.err.println("Missing expected response: " + EXPECTED);
                stat.addStatus(TEST_NAME, stat.FAIL);
            }
        } catch (Exception ex) {
            stat.addStatus(TEST_NAME, stat.FAIL);
            ex.printStackTrace();
        }
    }

    private void invokeSetup() throws Exception {

        System.out.println("Host=" + host + ", port=" + port);
        // access test.jsp
        Socket sock = new Socket(host, new Integer(port).intValue());
        OutputStream os = sock.getOutputStream();
        String get = "GET " + contextRoot + "/test.jsp?size="+ size + "&ascii=" + isAscii + " HTTP/1.0\r\n";
        System.out.println(get);
        os.write(get.getBytes());
        os.write("Host: localhost\r\n".getBytes());
        os.write("Connection: close\r\n".getBytes());
        os.write("\r\n".getBytes());
        InputStream is = sock.getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(is));

        // Get the JSESSIONID from the response
        String line = null;
        String cookieLine = null;
        while ((line = br.readLine()) != null) {
            if (line.startsWith("Set-Cookie:")
                    || line.startsWith("Set-cookie:")) {
                cookieLine = line;
                System.out.println(cookieLine);
            }
        }
        br.close();
        is.close();
        os.close();
        sock.close();

        if (cookieLine == null) {
            throw new Exception("Missing Set-Cookie response header");
        }

        jsessionId = getSessionIdFromCookie(cookieLine, JSESSIONID);
    }



    private boolean invoke(String uri) throws Exception {
        char[] chars = (isAscii)? ascii : jp;
        StringBuffer sb = new StringBuffer(size + formName.length() + 1
                + chars.length);
        while (sb.length() < size) {
            sb.append(chars);
        }
        if (sb.length() > size) {
            sb.delete(size, sb.length());
        }
        String data = sb.toString();
        Socket sock = new Socket(host, new Integer(port).intValue());
        OutputStream os = sock.getOutputStream();
        String boundary = "AaB03x";

        StringBuffer postData = new StringBuffer();
        postData.append("--" + boundary + "\r\n");
        postData.append("Content-Disposition: form-data; name=\"" + formName + "\"\r\n\r\n");
        postData.append(data + "\r\n");
        postData.append("--" + boundary + "--\r\n");
        byte[] postDataBytes = postData.toString().getBytes("UTF-8");

        String post = "POST " + contextRoot + uri + " HTTP/1.1\r\n";
        StringBuffer postReqHeader = new StringBuffer();
        postReqHeader.append(post);
        postReqHeader.append("Host: localhost\r\n");
        postReqHeader.append("Cookie: " + jsessionId + "\r\n");
        postReqHeader.append("Content-type: multipart/form-data; boundary=" + boundary + "\r\n");
        postReqHeader.append("Content-Length: " + postDataBytes.length + "\r\n\r\n");

        System.out.println(postReqHeader);
        os.write(postReqHeader.toString().getBytes());
        os.write(postDataBytes);

        InputStream is = sock.getInputStream();
        BufferedReader bis = new BufferedReader(new InputStreamReader(is));

        String line = null;
        boolean hasExpectedResponse = false;
        while ((line = bis.readLine()) != null) {
            System.out.println(line);
            if (line.equals(EXPECTED)) {
                hasExpectedResponse = true;
                break;
            }
        }
        bis.close();
        is.close();
        os.close();
        sock.close();

        return hasExpectedResponse;
    }

    private String getSessionIdFromCookie(String cookie, String field) {

        String ret = null;

        int index = cookie.indexOf(field);
        if (index != -1) {
            int endIndex = cookie.indexOf(';', index);
            if (endIndex != -1) {
                ret = cookie.substring(index, endIndex);
            } else {
                ret = cookie.substring(index);
            }
            ret = ret.trim();
        }

        return ret;
    }
}
