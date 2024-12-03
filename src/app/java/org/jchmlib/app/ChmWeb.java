/*
 * Copyright 2017 chimenchen. All rights reserved.
 */

package org.jchmlib.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import jchmlib.ChmCollectFilesEnumerator;
import jchmlib.ChmFile;
import jchmlib.ChmIndexSearcher;
import jchmlib.ChmSearchEnumerator;
import jchmlib.ChmTopicsTree;
import jchmlib.ChmUnitInfo;
import org.jchmlib.app.net.HttpRequest;
import org.jchmlib.app.net.HttpResponse;

/**
 * A simple web server.
 * You can use it to view CHM files.
 */
@SuppressWarnings("WeakerAccess")
public class ChmWeb extends Thread {

    private static final Logger LOG = Logger.getLogger(ChmWeb.class.getName());
    final boolean isRunningFromJar;
    ChmFile chmFile;
    String encoding = "UTF8";
    String resourcesPath;
    ChmTopicsTree filesTree = null;
    int totalFiles = 0;
    private ChmIndexEngine engine = null;
    private ServerSocket listen_socket;
    private String chmFilePath = null;

    public ChmWeb() {
        isRunningFromJar = checkRunningFromJar();

        resourcesPath = System.getProperty("org.jchmlib.app.ChmWeb.resources");
        if (resourcesPath == null && !isRunningFromJar) {
            resourcesPath = "resources";
        }
    }

    // reason: some CHM file may use the wrong encoding.
    private String fixEncoding(String originCodec) {
        // for CJK or the like, use the origin encoding.
        // see EncodingHelper for encoding names.
        if (!originCodec.equalsIgnoreCase("Latin1") &&
                !originCodec.startsWith("CP")) {
            return originCodec;
        }

        return "UTF8";
    }

    public boolean serveChmFile(int port, String chmFileName) {
        if (getState() == State.RUNNABLE) {  // already started
            return false;
        }

        try {
            chmFilePath = chmFileName;
            chmFile = new ChmFile(chmFileName);
            encoding = fixEncoding(chmFile.getEncoding());
        } catch (Exception e) {
            System.err.println("Failed to open this CHM file.");
            e.printStackTrace();
            return false;
        }

        listen_socket = tryCreateSocket(port);
        if (listen_socket == null) {
            System.err.println("Failed to find a free port.");
            return false;
        }

        System.out.println("Server started. Now open your browser " +
                "and type\n\t http://localhost:" + listen_socket.getLocalPort() + "/@index.html");

        //Start running Server thread
        start();

        return true;
    }

    private ServerSocket tryCreateSocket(int defaultPort) {
        if (defaultPort > 0) {
            try {
                return new ServerSocket(defaultPort);
            } catch (IOException ex) {
                return null;
            }
        }

        for (int port = 50000; port < 63000; port++) {
            try {
                return new ServerSocket(port);
            } catch (IOException ex) {
                // try next port
            }
        }

        return null;
    }

    public int getServerPort() {
        if (listen_socket == null) {
            return 0;
        } else {
            return listen_socket.getLocalPort();
        }
    }

    public String getChmTitle() {
        if (chmFile == null) {
            return "";
        } else {
            return chmFile.getTitle();
        }
    }

    public String getChmFilePath() {
        return chmFilePath == null ? "" : chmFilePath;
    }

    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Socket client_socket = listen_socket.accept();
                    new ClientHandler(client_socket, this);
                } catch (SocketException ignored) {
                    break;
                }
            }
        } catch (IOException e) {
            // System.err.println(e);
            e.printStackTrace();
        }
    }

    public void stopServer() {
        if (chmFile == null || listen_socket == null ||
                Thread.currentThread().isInterrupted()) {
            return;
        }

        try {
            listen_socket.close();
        } catch (IOException e) {
            LOG.fine("Error closing listen socket: " + e);
        }

        interrupt();

        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    boolean checkRunningFromJar() {
        String className = this.getClass().getName().replace('.', '/');
        String classJar = this.getClass().getResource("/" + className + ".class").toString();
        return classJar.startsWith("jar:");
    }

    InputStream getResourceAsStream(String requestedFile) {
        try {
            InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(requestedFile);
            if(inputStream!=null){
                return inputStream;
            }
            if (resourcesPath != null) {
                File f = new File(resourcesPath, requestedFile);
                if (f.canRead()) {
                    return new FileInputStream(f);
                } else if (!isRunningFromJar) {
                    return null;
                }
            }

            return ChmWeb.class.getResourceAsStream("/" + requestedFile);
        } catch (Exception ignored) {
            LOG.info("Failed to get resource " + requestedFile + ": " + ignored);
            return null;
        }
    }

    public ChmIndexEngine getIndexEngine() {
        if (engine == null) {
            engine = new ChmIndexEngine(chmFile, chmFilePath);
            addStopWordsToIndexEngine();
            engine.readIndex();
        }
        return engine;
    }

    private void addStopWordsToIndexEngine() {
        InputStream in = getResourceAsStream("stopwords.txt");
        if (in == null) {
            return;
        }
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            String word;
            while ((word = reader.readLine()) != null) {
                engine.addStopWords(word);
            }
        } catch (Exception ignored) {
            LOG.info("Error adding stop words: " + ignored);
        } finally {
            try {
                in.close();
            } catch (Throwable ignore) {
            }
        }
    }
}

/**
 * The ClientHandler class -- this is where HTTP requests are handled
 */
class ClientHandler extends Thread {

    private static final Logger LOG = Logger.getLogger(ClientHandler.class.getName());

    private final Socket client;
    private final ChmFile chmFile;
    private final ChmWeb server;
    // --Commented out by Inspection (17/8/3 21:56):private final boolean isRunningFromJar;
    // --Commented out by Inspection (17/8/3 21:56):private final String resourcesPath;
    private String encoding;
    private HttpRequest request;
    private HttpResponse response;
    private String requestedFile;

    ClientHandler(Socket client_socket, ChmWeb server) {
        client = client_socket;
        chmFile = server.chmFile;
        this.server = server;
        this.encoding = server.encoding;
        // this.isRunningFromJar = server.isRunningFromJar;
        // this.resourcesPath = server.resourcesPath;

        try {
            request = new HttpRequest(client.getInputStream(), this.encoding);
            requestedFile = request.getPath();
            if (requestedFile != null && requestedFile.startsWith("/chmweb/")) {
                this.encoding = "UTF8";
                request.setEncoding(this.encoding);  // for parsing parameters
            }
            response = new HttpResponse(client.getOutputStream(), this.encoding);
        } catch (IOException e) {
            e.printStackTrace();
            try {
                client.close();
            } catch (IOException e2) {
                e.printStackTrace();
            }
            return;
        }

        if (requestedFile == null || requestedFile.length() == 0) {
            return;
        }

        start();
    }

    private static String quoteJSON(String str) {
        if (str == null || str.length() == 0) {
            return "\"\"";
        }

        int i;
        int len = str.length();
        StringBuilder sb = new StringBuilder(len + 4);
        String t;

        sb.append('"');
        for (i = 0; i < len; i += 1) {
            char c = str.charAt(i);
            switch (c) {
                case '\\':
                case '"':
                    sb.append('\\');
                    sb.append(c);
                    break;
                case '/':
                    sb.append('\\');
                    sb.append(c);
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                default:
                    if (c < ' ') {
                        t = "000" + Integer.toHexString(c);
                        sb.append("\\u").append(t.substring(t.length() - 4));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    public void run() {
        try {
            if (requestedFile.equals("/")) {
                requestedFile = requestedFile.substring(1);
                deliverSpecial();
            } else if (requestedFile.equalsIgnoreCase("/favicon.ico")) {
                requestedFile = requestedFile.substring(1);
                deliverSpecial();
            } else if (requestedFile.startsWith("/@")) {
                requestedFile = requestedFile.substring(2);
                deliverSpecial();
            } else if (requestedFile.startsWith("/chmweb/")) {
                requestedFile = requestedFile.substring("/chmweb/".length());
                deliverSpecial();
            } else if (requestedFile.endsWith("/")) {// this is a directory
                if (requestedFile.equals("/nonchmweb/")) {
                    requestedFile = "/";
                }
                deliverDir();
            } else { // this is a file
                deliverFile();
            }
        } catch (IOException e) {
            LOG.fine("Failed to handle request:  " + e);
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private String fixChmLink(String homeFile) {
        if (homeFile.equals("/")) {
            return "/nonchmweb/";
        } else if (homeFile.startsWith("/chmweb/")) {
            return "/nonchmweb/" + homeFile.substring("/chmweb/".length());
        } else {
            return homeFile;
        }
    }

    private void deliverDir() {
        response.sendHeader("text/html");
        response.sendString("<html>\n" +
                "<head>\n" +
                "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=" + encoding
                + "\">\n"
                +
                "<title>" + requestedFile + "</title>" +
                "<link rel=\"stylesheet\" href=\"/chmweb/css/chmweb.css\">" +
                "</head>" +
                "<body>\n" +
                "<h1>" + requestedFile + "</h1>" +
                "<table class=\"filelist\">\n" +
                "<thead>\n" +
                "<tr>\n" +
                "  <td>File</td>\n" +
                "  <td class=\"filesize\">Size</td>\n" +
                "</tr>\n" +
                "<thead>\n" +
                "<tbody>\n");

        // /apple/ l=7, 0-6, 0, 0-1
        // /apple/banana/, l=14, 0-13, 6, 0-7
        // / l=1, 0-0
        int index = requestedFile.substring(0, requestedFile.length() - 1).lastIndexOf("/");
        if (index >= 0) {
            String parentDir = requestedFile.substring(0, index + 1);
            parentDir = fixChmLink(parentDir);
            response.sendLine(String.format("<td><a href=\"%s\">%s</a></td>", parentDir, ".."));
            response.sendLine("<td></td>");
        }

        ChmCollectFilesEnumerator enumerator = new ChmCollectFilesEnumerator();
        chmFile.enumerateDir(requestedFile, ChmFile.CHM_ENUMERATE_USER, enumerator);

        for (ChmUnitInfo ui : enumerator.files) {
            response.sendLine("<tr>");
            if (ui.getLength() > 0) {
                response.sendLine(String.format("<td class=\"file\"><a href=\"%s\">%s</a></td>",
                        fixChmLink(ui.getPath()), ui.getPath().substring(requestedFile.length())));
                response.sendLine(String.format("<td class=\"filesize\">%d</td>", ui.getLength()));
            } else {
                response.sendLine(String.format("<td class=\"folder\"><a href=\"%s\">%s</a></td>",
                        fixChmLink(ui.getPath()), ui.getPath().substring(requestedFile.length())));
                response.sendLine("<td></td>");
            }
            response.sendLine("</tr>");
        }

        response.sendString("</tbody>\n" +
                "</table>\n" +
                "</body>\n" +
                "</html>\n");
    }

    private void deliverFile() {
        // resolve object
        ChmUnitInfo ui = chmFile.resolveObject(requestedFile);

        String mimeType = request.getContentType(requestedFile);
        response.sendHeader(mimeType);

        // check to see if file exists
        if (ui == null) {
            if (mimeType.equalsIgnoreCase("text/html")) {
                response.sendString("<html>\n" +
                        "<head>\n" +
                        "<meta http-equiv=\"Content-Type\" content=\"text/html; " +
                        " charset=" + encoding + "\">\n" +
                        "<title>404</title>" +
                        "</head>" +
                        "<body>\n" +
                        "404: not found: " + requestedFile +
                        "</body>");
            }
        } else {
            ByteBuffer buffer = chmFile.retrieveObject(ui);
            response.write(buffer, (int) ui.getLength());
        }
    }

    private void deliverSpecial() throws IOException {
        if (requestedFile.length() == 0 || requestedFile.equalsIgnoreCase("index.html")) {
            deliverMain();
        } else if (requestedFile.equalsIgnoreCase("topics.json")) {
            deliverTopicsTree();
        } else if (requestedFile.equalsIgnoreCase("files.json")) {
            deliverFilesTree();
        } else if (requestedFile.equalsIgnoreCase("search.json")) {
            deliverUnifiedSearch();
        } else if (requestedFile.equalsIgnoreCase("index.json")) {
            deliverBuildIndex();
        } else if (requestedFile.equalsIgnoreCase("search3.json")) {
            deliverSearch3();
        } else if (requestedFile.equalsIgnoreCase("info.json")) {
            deliverInfo();
        } else {
            deliverResource(requestedFile);
        }
    }

    private void deliverMain() {
        response.sendHeader("text/html");
        String homeFile = fixChmLink(chmFile.getHomeFile());
        response.sendLine(String.format(
                "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Frameset//EN\"\n"
                        + "    \"http://www.w3.org/TR/html4/frameset.dtd\">\n"
                        + "<html>\n"
                        + "<head>\n"
                        + "  <title>%s</title>\n"
                        + "  <meta http-equiv=\"Content-Type\" content=\"text/html; charset=%s\">\n"
                        + "</head>\n"
                        + "<frameset cols=\"200, *\">\n"
                        + "  <frame src=\"/chmweb/sidebar.html\" name=\"treefrm\">\n"
                        + "  <frame src=\"%s\" name=\"basefrm\">\n"
                        + "  <noframes>\n"
                        + "    <noscript>\n"
                        + "      <div>JavaScript is disabled on your browser.</div>\n"
                        + "    </noscript>\n"
                        + "    <h2>Frame Alert</h2>\n"
                        + "    <p>This document is designed to be viewed using the frames feature.\n"
                        + "      If you see this message, you are using a non-frame-capable web client.\n"
                        + "      Link to <a href=\"%s\">Main Page</a>.</p>\n"
                        + "  </noframes>\n"
                        + "</frameset>\n"
                        + "</html>\n",
                chmFile.getTitle(), encoding, homeFile, homeFile));
    }

    private ChmTopicsTree findSubtreeByID(ChmTopicsTree root, int treeID) {
        if (root.id == treeID) {
            return root;
        }

        for (ChmTopicsTree child : root.children) {
            ChmTopicsTree result = findSubtreeByID(child, treeID);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    private ChmTopicsTree allowSubtreeRequest(ChmTopicsTree tree) {
        String sTreeID = request.getParameter("id");
        if (sTreeID != null) {
            try {
                int treeID = Integer.parseInt(sTreeID);
                if (treeID > 0) {
                    return findSubtreeByID(tree, treeID);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return tree;
    }

    private void deliverTopicsTree() {
        ChmTopicsTree tree = chmFile.getTopicsTree();
        int maxLevel = tree != null && tree.id > 10000 ? 2 : 100;

        tree = allowSubtreeRequest(tree);
        if (tree == null) {
            return;
        }

        response.sendHeader("application/json");
        printTopicsTree(tree, 0, maxLevel);
        chmFile.releaseLargeTopicsTree(false);
    }

    private void printTopicsTree(ChmTopicsTree tree, int level, int maxLevel) {
        if (tree == null) {
            return;
        }

        String title = tree.title.length() > 0 ? tree.title : "untitled";
        if (!tree.children.isEmpty()) {
            if (level == 0) {
                response.sendString("[");
            } else if (level == maxLevel) {
                if (tree.id > 0) {
                    response.sendLine(String.format("[%s, %s, \"load-by-id\", %d]",
                            quoteJSON(tree.path), quoteJSON(title), tree.id));
                } else {
                    response.sendLine(
                            String.format("[%s, %s]", quoteJSON(tree.path), quoteJSON(title)));
                }
                return;
            } else {
                response.sendLine(
                        String.format("[%s, %s, [", quoteJSON(tree.path), quoteJSON(title)));
            }

            int i = 0;
            for (ChmTopicsTree child : tree.children) {
                printTopicsTree(child, level + 1, maxLevel);
                if (i != tree.children.size() - 1) {
                    response.sendLine(", ");
                }
                i++;
            }

            if (level == 0) {
                response.sendLine("]");
            } else {
                response.sendString("]]");
            }
        } else { // leaf node
            if (tree.path.length() == 0 && title.equalsIgnoreCase("untitled")) {
                response.sendString("[]");
            } else {
                String path = fixChmLink(tree.path);
                response.sendString(String.format("[%s, %s]", quoteJSON(path), quoteJSON(title)));
            }
        }
    }

    private void deliverFilesTree() {
        if (server.filesTree == null) {
            ChmCollectFilesEnumerator enumerator = new ChmCollectFilesEnumerator();
            chmFile.enumerate(ChmFile.CHM_ENUMERATE_USER, enumerator);
            server.filesTree = buildFilesTree(enumerator.files);
            server.totalFiles = enumerator.files.size();
        }
        ChmTopicsTree tree = server.filesTree;
        int maxLevel = server.totalFiles > 10000 ? 2 : 100;

        tree = allowSubtreeRequest(tree);
        if (tree == null) {
            return;
        }

        response.sendHeader("application/json");
        printTopicsTree(tree, 0, maxLevel);
    }

    private ChmTopicsTree addFileNode(String path, String title, ChmTopicsTree currentDirNode) {
        ChmTopicsTree node = new ChmTopicsTree();
        node.path = path;
        node.title = title;
        node.parent = currentDirNode;
        currentDirNode.children.add(node);
        return node;
    }

    private ChmTopicsTree buildFilesTree(ArrayList<ChmUnitInfo> files) {
        int currentID = 0;
        ChmTopicsTree root = new ChmTopicsTree();
        root.path = "/";
        root.id = currentID++;
        ChmTopicsTree currentDirNode = root;

        addFileNode(fixChmLink(chmFile.getHomeFile()), "Main Page", root);
        addFileNode(fixChmLink("/"), "Root Directory", root);
        for (ChmUnitInfo ui : files) {
            String path = ui.getPath();
            if (path.equals("/")) {
                continue;
            }

            while (currentDirNode != null && !path.startsWith(currentDirNode.path)) {
                currentDirNode = currentDirNode.parent;
            }
            if (currentDirNode == null) {
                break;
            }

            String title = path.substring(currentDirNode.path.length());
            while (true) {
                if (title.length() == 0) {
                    break;
                }
                int index = title.indexOf("/");
                if (index <= 0 || index == title.length() - 1) {
                    break;
                }

                String dirPart = title.substring(0, index + 1);
                String leftPart = title.substring(index + 1);
                currentDirNode = addFileNode(currentDirNode.path + dirPart,
                        dirPart, currentDirNode);
                currentDirNode.id = currentID++;

                title = leftPart;
            }

            ChmTopicsTree node = addFileNode(path, title, currentDirNode);
            if (path.endsWith("/")) {
                currentDirNode = node;
                currentDirNode.id = currentID++;
            }
        }

        return root;
    }

    private void deliverUnifiedSearch() {
        String query = request.getParameter("q");
        if (query == null) {
            LOG.fine("empty query");
            return;
        }
        boolean useRegex = false;
        String sUseRegex = request.getParameter("regex");
        if (sUseRegex != null && (sUseRegex.equals("1") || sUseRegex.equalsIgnoreCase("true"))) {
            useRegex = true;
        }
        LOG.fine(String.format("query: %s, regex: %s", query, useRegex));

        int maxResults = 300;

        if (!useRegex) {
            ChmIndexSearcher searcher = chmFile.getIndexSearcher();
            if (!searcher.notSearchable) {
                HashMap<String, String> results = searcher.search(query, false, false, maxResults);
                deliverSearchResults(results);
                return;
            }
            ChmIndexEngine engine = server.getIndexEngine();
            if (engine.isSearchable()) {
                HashMap<String, String> results = engine.search(query, true, false, maxResults);
                deliverSearchResults(results);
                return;
            }
        }

        try {
            ChmSearchEnumerator enumerator = new ChmSearchEnumerator(chmFile, query, maxResults);
            chmFile.enumerate(ChmFile.CHM_ENUMERATE_USER, enumerator);
            HashMap<String, String> results = enumerator.getResults();
            deliverSearchResults(results);
        } catch (Exception e) {
            LOG.fine("Failed to handle search:  " + e);
        }
    }

    private void deliverSearchResults(HashMap<String, String> results) {
        response.sendHeader("application/json");
        if (results != null && results.size() > 0) {
            response.sendLine("{\"ok\": true, \"results\":[");
            int i = 0;
            for (Map.Entry<String, String> entry : results.entrySet()) {
                String url = entry.getKey();
                String topic = entry.getValue();
                url = fixChmLink(url);
                if (i > 0) {
                    response.sendLine(",");
                }
                response.sendString(
                        String.format("[%s, %s]", quoteJSON(url), quoteJSON(topic)));
                i++;
            }
            response.sendLine("]}");
        } else {
            response.sendLine("{\"ok\": false}");
        }
    }

    private void deliverResource(String requestedFile) throws IOException {
        InputStream in = server.getResourceAsStream(requestedFile);
        if (in == null) {
            response.sendHeader("text/plain");
            response.sendString("404: not found: " + requestedFile);
            return;
        }

        response.sendHeader(request.getContentType(requestedFile));

        byte[] buffer = new byte[1024];
        int size;
        while ((size = in.read(buffer)) != -1) {
            response.write(buffer, 0, size);
        }

        try {
            in.close();
        } catch (Throwable ignore) {
        }
    }

    private void deliverBuildIndex() {
        final ChmIndexEngine engine = server.getIndexEngine();
        if (engine.getBuildIndexStep() < 0) {
            new Thread() {
                public void run() {
                    engine.buildIndex();
                }
            }.start();
        }
        response.sendHeader("application/json");
        response.sendLine(String.format("{\"step\": %d}", engine.getBuildIndexStep()));
    }

    private void deliverSearch3() {
        String query = request.getParameter("q");
        if (query == null) {
            LOG.fine("empty query");
            return;
        }
        LOG.fine(String.format("query: %s", query));

        ChmIndexEngine engine = server.getIndexEngine();
        if (!engine.isSearchable()) {
            return;
        }

        HashMap<String, String> results = engine.search(query, true, false, 0);
        deliverSearchResults(results);
    }

    private void deliverInfo() {
        response.sendHeader("application/json");
        if (chmFile == null) {
            response.sendLine("{\"ok\": false}");
            return;
        }

        response.sendLine("{");
        response.sendLine(String.format("%s: %s,",
                quoteJSON("title"), quoteJSON(chmFile.getTitle())));
        response.sendLine(String.format("%s: %s,",
                quoteJSON("encoding"), quoteJSON(chmFile.getEncoding())));
        response.sendLine(String.format("%s: %s,",
                quoteJSON("homeFile"), quoteJSON(chmFile.getHomeFile())));

        ChmIndexSearcher searcher = chmFile.getIndexSearcher();
        response.sendLine(String.format("%s: %s,",
                quoteJSON("hasIndex"), !searcher.notSearchable));

        if (searcher.notSearchable) {
            ChmIndexEngine engine = server.getIndexEngine();
            response.sendLine(String.format("%s: %d,",
                    quoteJSON("buildIndexStep"), engine.getBuildIndexStep()));
        }

        response.sendLine("\"ok\": true");

        response.sendLine("}");
    }
}

