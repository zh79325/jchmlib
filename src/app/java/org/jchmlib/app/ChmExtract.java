package org.jchmlib.app;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import jchmlib.ChmEnumerator;
import jchmlib.ChmFile;
import jchmlib.ChmUnitInfo;

@SuppressWarnings("WeakerAccess")
public class ChmExtract {

    public static void main(String[] argv) throws IOException {
        argv=new String[]{"/Users/eleme/Desktop/hk/播放库编程指南V7.3.9.x.chm","chm/hk_play"};
        if (argv.length < 1) {
            System.out.println("Usage: ChmExtract <chmfile> <output-directory>");
            return;
        }

        long time_prev = System.currentTimeMillis();

        ChmFile chmFile = new ChmFile(argv[0]);

        System.out.println("/:" + argv[0]);
        chmFile.enumerate(ChmFile.CHM_ENUMERATE_ALL,
                new Extractor(chmFile, argv[1]));
        long time = System.currentTimeMillis();
        System.out.println("    finished in " + (time - time_prev) + " ms");
        System.out.println();
    }
}

class Extractor implements ChmEnumerator {

    private final String basePath;
    private final ChmFile chmFile;


    public Extractor(ChmFile chmFile, String basePath) {
        this.chmFile = chmFile;
        if (basePath.endsWith("/")) {
            this.basePath = basePath.substring(0,
                    basePath.length() - 1);
        } else {
            this.basePath = basePath;
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void enumerate(ChmUnitInfo ui) {
        String path = ui.getPath();
        long length = ui.getLength();
        if (!path.startsWith("/")) {
            return;
        }

        File file = new File(basePath, path);
        if(file.exists()){
            file.delete();
        }
        File parentFile = file.getParentFile();
        if(!parentFile.exists()){
            parentFile.mkdirs();
        }

        String fullPath = file.toString();
        System.out.println("out put  path=>"+file.getAbsolutePath());
        if (length != 0) {
            PrintStream out = null;
            try {
                file.createNewFile();
                out = new PrintStream(fullPath);
            } catch (IOException e) {
                System.out.println("   fail while opening the newly created file "
                        + path);
            }
            if (out == null) {
                System.out.println("   fail to open the newly created file "
                        + path);
                return;
            }

            ByteBuffer buffer = chmFile.retrieveObject(ui, 0, length);
            if (buffer == null) {
                System.out.println("    extract failed on " + path);
                return;
            }
            int gotLen = buffer.limit() - buffer.position();
            byte[] bytes = new byte[gotLen];

            buffer.mark();
            while (buffer.hasRemaining()) {
                buffer.get(bytes);
                out.write(bytes, 0, gotLen);
            }
            buffer.reset();
            out.close();
        } else {
            if (fullPath.endsWith("/")) {
                new File(fullPath).mkdirs();
            } else {
                new File(fullPath).delete();
            }
        }
    }
}
