// ABOUTME: Exception thrown on invalid JSON input.
// ABOUTME: Carries the failure's byte offset, line and column, also as ex-data.

package com.s_exp.oda;

import clojure.lang.IExceptionInfo;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;

public final class JsonParseException extends RuntimeException implements IExceptionInfo {
    private static final long serialVersionUID = 1L;

    private static final Keyword OFFSET = Keyword.intern("offset");
    private static final Keyword LINE = Keyword.intern("line");
    private static final Keyword COLUMN = Keyword.intern("column");

    /** Byte offset into the input. */
    public final int position;
    /** 1-based line number. */
    public final int line;
    /** 1-based column, counted in characters (code points). */
    public final int column;

    public JsonParseException(String message, int position, int line, int column) {
        super(message + " at line " + line + ", column " + column + " (offset " + position + ")");
        this.position = position;
        this.line = line;
        this.column = column;
    }

    /** {:offset :line :column} */
    @Override
    public IPersistentMap getData() {
        return new PersistentArrayMap(new Object[] {
                OFFSET, (long) position, LINE, (long) line, COLUMN, (long) column});
    }
}
