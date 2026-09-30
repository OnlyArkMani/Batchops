package com.batchops.io;

import com.batchops.domain.StageException;
import com.batchops.domain.Table;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Picks a parser from the file extension. */
public final class TableLoader {

    private TableLoader() {
    }

    public record Options(Charset charset) {
        public static Options defaults() {
            return new Options(StandardCharsets.UTF_8);
        }
    }

    public static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    public static Table load(String name, byte[] data, Options options) throws IOException {
        String ext = extension(name);
        switch (ext) {
            case ".csv":
            case ".txt":
                try {
                    return TableIO.parseCsv(TableIO.decode(data, options.charset()));
                } catch (CharacterCodingException e) {
                    throw StageException.permanent(
                            "UnicodeDecodeError: codec can't decode file as " + options.charset().name(), e);
                }
            case ".xlsx":
            case ".xls":
                return XlsxReader.read(data);
            case ".pdf":
                return PdfTableReader.read(data);
            default:
                throw StageException.permanent("Unsupported file type: " + ext);
        }
    }

    public static Table load(String name, byte[] data) throws IOException {
        return load(name, data, Options.defaults());
    }
}
