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

    /** Byte offset into the input (a stream's: since its start). */
    public final long position;
    /** 1-based line number. */
    public final int line;
    /** 1-based column, counted in characters (code points). */
    public final int column;
    /** What went wrong, without the position suffix. */
    final String reason;

    public JsonParseException(String reason, long position, int line, int column) {
        super(reason + " at line " + line + ", column " + column + " (offset " + position + ")");
        this.reason = reason;
        this.position = position;
        this.line = line;
        this.column = column;
    }

    /** {:offset :line :column} */
    @Override
    public IPersistentMap getData() {
        return new PersistentArrayMap(new Object[] {
                OFFSET, position, LINE, (long) line, COLUMN, (long) column});
    }
}
