/*
 * Copyright 2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.grails.web.util;

import groovy.lang.GroovyObject;
import groovy.lang.MetaClass;
import groovy.lang.Writable;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.groovy.grails.support.encoding.EncodedAppender;
import org.codehaus.groovy.grails.support.encoding.EncodedAppenderFactory;
import org.codehaus.groovy.grails.support.encoding.EncodedAppenderWriter;
import org.codehaus.groovy.grails.support.encoding.EncodedAppenderWriterFactory;
import org.codehaus.groovy.grails.support.encoding.Encoder;
import org.codehaus.groovy.grails.support.encoding.EncodingStateRegistry;
import org.codehaus.groovy.grails.support.encoding.WriterEncodedAppender;
import org.codehaus.groovy.grails.web.util.StreamCharBuffer.StreamCharBufferWriter;
import org.codehaus.groovy.runtime.GStringImpl;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.typehandling.DefaultTypeTransformation;

/**
 * PrintWriter implementation that doesn't have synchronization. null object
 * references are ignored in print methods (nothing gets printed)
 *
 * @author Lari Hotari, Sagire Software Oy
 */
public class GrailsPrintWriter extends Writer implements GrailsWrappedWriter, EncodedAppenderWriterFactory, GroovyObject {
    protected static final Log LOG = LogFactory.getLog(GrailsPrintWriter.class);
    protected static final char CRLF[] = { '\r', '\n' };
    protected boolean trouble = false;
    protected Writer out;
    protected boolean allowUnwrappingOut = true;
    protected boolean usageFlag = false;
    protected Writer streamCharBufferTarget = null;
    protected Writer previousOut = null;

    public GrailsPrintWriter(Writer out) {
        this.metaClass = InvokerHelper.getMetaClass(this.getClass());
        setOut(out);
    }

    public boolean isAllowUnwrappingOut() {
        return allowUnwrappingOut;
    }

    public Writer unwrap() {
        if (isAllowUnwrappingOut()) {
            return getOut();
        }
        return this;
    }

    public Writer getOut() {
        return out;
    }

    public void setOut(Writer newOut) {
        this.out = unwrapWriter(newOut);
        this.streamCharBufferTarget = null;
        this.previousOut = null;
    }

    protected Writer unwrapWriter(Writer writer) {
        if (writer instanceof GrailsWrappedWriter ) {
            return ((GrailsWrappedWriter)writer).unwrap();
        }
        return writer;
    }

    /**
     * Provides Groovy << left shift operator, but intercepts call to make sure
     * nulls are converted to "" strings
     *
     * @param value The value
     * @return Returns this object
     * @throws IOException
     */
    public GrailsPrintWriter leftShift(Object value) throws IOException {
        usageFlag = true;
        if (value != null) {
            InvokerHelper.write(this, value);
        }
        return this;
    }

    public GrailsPrintWriter plus(Object value) throws IOException {
        usageFlag = true;
        return leftShift(value);
    }

    /**
     * Flush the stream if it's not closed and check its error state. Errors are
     * cumulative; once the stream encounters an error, this routine will return
     * true on all successive calls.
     *
     * @return true if the print stream has encountered an error, either on the
     *         underlying output stream or during a format conversion.
     */
    public boolean checkError() {
        return trouble;
    }

    public void setError() {
        trouble = true;
    }

    /**
     * Flush the stream.
     *
     * @see #checkError()
     */
    @Override
    public synchronized void flush() {
        if (trouble) {
            return;
        }

        try {
            out.flush();
        }
        catch (IOException e) {
            handleIOException(e);
        }
    }

    boolean isTrouble() {
        return trouble;
    }

    void handleIOException(IOException e) {
        if (trouble) {
            return;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("I/O exception in GrailsPrintWriter: " + e.getMessage(), e);
        }
        trouble = true;
        setError();
    }

    /**
     * Print an object. The string produced by the <code>{@link
     * java.lang.String#valueOf(Object)}</code> method is translated into bytes
     * according to the platform's default character encoding, and these bytes
     * are written in exactly the manner of the <code>{@link #write(int)}</code>
     * method.
     *
     * @param obj The <code>Object</code> to be printed
     * @see java.lang.Object#toString()
     */
    public void print(final Object obj) {
        if (trouble || obj == null) {
            usageFlag = true;
            return;
        }

        Class<?> clazz = obj.getClass();
        if (clazz == String.class) {
            write((String)obj);
        }
        else if (clazz == StreamCharBuffer.class) {
            write((StreamCharBuffer)obj);
        }
        else if (clazz == GStringImpl.class) {
            write((Writable)obj);
        }
        else if (obj instanceof Writable) {
            write((Writable)obj);
        }
        else if (obj instanceof CharSequence) {
            try {
                usageFlag = true;
                out.append((CharSequence)obj);
            }
            catch (IOException e) {
                handleIOException(e);
            }
        }
        else {
            write(String.valueOf(obj));
        }
    }

    /**
     * Print a string. If the argument is <code>null</code> then the string
     * <code>""</code> is printed. Otherwise, the string's characters are
     * converted into bytes according to the platform's default character
     * encoding, and these bytes are written in exactly the manner of the
     * <code>{@link #write(int)}</code> method.
     *
     * @param s The <code>String</code> to be printed
     */
    public void print(final String s) {
        if (s == null) {
            usageFlag = true;
            return;
        }
        write(s);
    }

    /**
     * Writes a string. If the argument is <code>null</code> then the string
     * <code>""</code> is printed.
     *
     * @param s The <code>String</code> to be printed
     */
    @Override
    public void write(final String s) {
        usageFlag = true;
        if (trouble || s == null) {
            return;
        }

        try {
            out.write(s);
        }
        catch (IOException e) {
            handleIOException(e);
        }
    }

    /**
     * Write a single character.
     *
     * @param c int specifying a character to be written.
     */
    @Override
    public void write(final int c) {
        usageFlag = true;
        if (trouble)
            return;

        try {
            out.write(c);
        }
        catch (IOException e) {
            handleIOException(e);
        }
    }

    /**
     * Write a portion of an array of characters.
     *
     * @param buf Array of characters
     * @param off Offset from which to start writing characters
     * @param len Number of characters to write
     */
    @Override
    public void write(final char buf[], final int off, final int len) {
        usageFlag = true;
        if (trouble || buf == null || len == 0)
            return;
        try {
            out.write(buf, off, len);
        }
        catch (IOException e) {
            handleIOException(e);
        }
    }

    /**
     * Write a portion of a string.
     *
     * @param s A String
     * @param off Offset from which to start writing characters
     * @param len Number of characters to write
     */
    @Override
    public void write(final String s, final int off, final int len) {
        usageFlag = true;
        if (trouble || s == null || s.length() == 0)
            return;

        try {
            out.write(s, off, len);
        }
        catch (IOException e) {
            handleIOException(e);
        }
    }

    @Override
    public void write(final char buf[]) {
        write(buf, 0, buf.length);
    }

    /** delegate methods, not synchronized **/

    public void print(final boolean b) {
        if (b) {
            write("true");
        }
        else {
            write("false");
        }
    }

    public void print(final char c) {
        write(c);
    }

    public void print(final int i) {
        write(String.valueOf(i));
    }

    public void print(final long l) {
        write(String.valueOf(l));
    }

    public void print(final float f) {
        write(String.valueOf(f));
    }

    public void print(final double d) {
        write(String.valueOf(d));
    }

    public void print(final char s[]) {
        write(s);
    }

    public void println() {
        usageFlag = true;
        write(CRLF);
    }

    public void println(final boolean b) {
        print(b);
        println();
    }

    public void println(final char c) {
        print(c);
        println();
    }

    public void println(final int i) {
        print(i);
        println();
    }

    public void println(final long l) {
        print(l);
        println();
    }

    public void println(final float f) {
        print(f);
        println();
    }

    public void println(final double d) {
        print(d);
        println();
    }

    public void println(final char c[]) {
        print(c);
        println();
    }

    public void println(final String s) {
        print(s);
        println();
    }

    public void println(final Object o) {
        print(o);
        println();
    }

    @Override
    public GrailsPrintWriter append(final char c) {
        try {
            usageFlag = true;
            out.append(c);
        }
        catch (IOException e) {
            handleIOException(e);
        }
        return this;
    }

    @Override
    public GrailsPrintWriter append(final CharSequence csq, final int start, final int end) {
        try {
            usageFlag = true;
            out.append(csq, start, end);
        }
        catch (IOException e) {
            handleIOException(e);
        }
        return this;
    }

    @Override
    public GrailsPrintWriter append(final CharSequence csq) {
        try {
            usageFlag = true;
            out.append(csq);
        }
        catch (IOException e) {
            handleIOException(e);
        }
        return this;
    }

    public GrailsPrintWriter append(final Object obj) {
        print(obj);
        return this;
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    public void write(final StreamCharBuffer otherBuffer) {
        usageFlag = true;
        if (trouble)
            return;

        try {
            otherBuffer.writeTo(findStreamCharBufferTarget(true));
        }
        catch (IOException e) {
            handleIOException(e);
        }
    }

    protected Writer findStreamCharBufferTarget(boolean markUsed) {
        boolean allowCaching = markUsed;

        Writer currentOut = getOut();
        if (allowCaching && streamCharBufferTarget != null && previousOut == currentOut) {
            return streamCharBufferTarget;
        }

        Writer target = currentOut;
        while (target instanceof GrailsWrappedWriter) {
            GrailsWrappedWriter gpr = ((GrailsWrappedWriter)target);
            if (gpr.isAllowUnwrappingOut()) {
                if (markUsed) {
                    gpr.markUsed();
                }
                target = gpr.unwrap();
            }
            else {
                break;
            }
        }

        Writer result;
        if (target instanceof StreamCharBufferWriter) {
            result = target;
        }
        else {
            result = currentOut;
        }

        if (allowCaching) {
            streamCharBufferTarget = result;
            previousOut = currentOut;
        }

        return result;
    }

    public void print(final StreamCharBuffer otherBuffer) {
        write(otherBuffer);
    }

    public void append(final StreamCharBuffer otherBuffer) {
        write(otherBuffer);
    }

    public void println(final StreamCharBuffer otherBuffer) {
        write(otherBuffer);
        println();
    }

    public GrailsPrintWriter leftShift(final StreamCharBuffer otherBuffer) {
        if (otherBuffer != null) {
            write(otherBuffer);
        }
        return this;
    }

    public void write(final Writable writable) {
        usageFlag = true;
        if (trouble)
            return;

        try {
            writable.writeTo(getOut());
        }
        catch (IOException e) {
            handleIOException(e);
        }
    }

    public void print(final Writable writable) {
        write(writable);
    }

    public GrailsPrintWriter leftShift(final Writable writable) {
        write(writable);
        return this;
    }

    public void print(final GStringImpl gstring) {
        write(gstring);
    }

    public GrailsPrintWriter leftShift(final GStringImpl gstring) {
        write(gstring);
        return this;
    }

    public boolean isUsed() {
        if (usageFlag) {
            return true;
        }

        Writer target = findStreamCharBufferTarget(false);
        if (target instanceof StreamCharBufferWriter) {
            StreamCharBuffer buffer = ((StreamCharBufferWriter)target).getBuffer();
            if (!buffer.isEmpty()) {
                return true;
            }
        }
        return usageFlag;
    }

    public void setUsed(boolean newUsed) {
        usageFlag = newUsed;
    }

    public boolean resetUsed() {
        boolean prevUsed = usageFlag;
        usageFlag = false;
        return prevUsed;
    }

    @Override
    public void close() {
        try {
            out.close();
        }
        catch (IOException e) {
            handleIOException(e);
        }
    }

    public void markUsed() {
        setUsed(true);
    }

    public Object asType(Class<?> clazz) {
        if (clazz == PrintWriter.class) {
            return asPrintWriter();
        }
        if (clazz == Writer.class) {
            return this;
        }
        return DefaultTypeTransformation.castToType(this, clazz);
    }

    public PrintWriter asPrintWriter() {
        return new GrailsPrintWriterAdapter(this);
    }

    public Writer getWriterForEncoder(Encoder encoder, EncodingStateRegistry encodingStateRegistry) {
        Writer target = null;
        if(getOut() instanceof EncodedAppenderWriterFactory) {
            target = getOut();
        } else {
            target = findStreamCharBufferTarget(false);
        }
        if (target instanceof EncodedAppenderWriterFactory) {
            return ((EncodedAppenderWriterFactory)target).getWriterForEncoder(encoder, encodingStateRegistry);
        } else if (target instanceof EncodedAppenderFactory) {
            EncodedAppender encodedAppender=((EncodedAppenderFactory)target).getEncodedAppender();
            return new EncodedAppenderWriter(encodedAppender, encoder, encodingStateRegistry);
        } else if (target != null) {
            return new CodecPrintWriter(target, encoder, encodingStateRegistry);
        } else {
            return null;
        }
    }

    // GroovyObject interface implementation to speed up metaclass operations
    private transient MetaClass metaClass;

    public Object getProperty(String property) {
        return getMetaClass().getProperty(this, property);
    }

    public void setProperty(String property, Object newValue) {
        getMetaClass().setProperty(this, property, newValue);
    }

    public Object invokeMethod(String name, Object args) {
        return getMetaClass().invokeMethod(this, name, args);
    }

    public MetaClass getMetaClass() {
        if (metaClass == null) {
            metaClass = InvokerHelper.getMetaClass(getClass());
        }
        return metaClass;
    }

    public void setMetaClass(MetaClass metaClass) {
        this.metaClass = metaClass;
    }
}
