package com.fasterxml.jackson.dataformat.yaml;

import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

import com.esotericsoftware.yamlbeans.tokenizer.*;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.base.ParserBase;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.core.util.BufferRecycler;
import com.fasterxml.jackson.core.util.ByteArrayBuilder;

/**
 * {@link JsonParser} implementation used to expose YAML documents
 * in form that allows other Jackson functionality to process YAML content,
 * such as binding POJOs to and from it, and building tree representations.
 */
public class YAMLParser extends ParserBase
{
    /**
     * Enumeration that defines all togglable features for YAML parsers.
     */
    public enum Feature {
        ;

        final boolean _defaultState;
        final int _mask;
        
        /**
         * Method that calculates bit set (flags) of all features that
         * are enabled by default.
         */
        public static int collectDefaults()
        {
            int flags = 0;
            for (Feature f : values()) {
                if (f.enabledByDefault()) {
                    flags |= f.getMask();
                }
            }
            return flags;
        }
        
        private Feature(boolean defaultState) {
            _defaultState = defaultState;
            _mask = (1 << ordinal());
        }
        
        public boolean enabledByDefault() { return _defaultState; }
        public int getMask() { return _mask; }
    }

    // note: does NOT include '0', handled separately
//    private final static Pattern PATTERN_INT = Pattern.compile("-?[1-9][0-9]*");

    /**
     * We will use pattern that is bit stricter than YAML definition,
     * but we will still allow things like extra '_' in there.
     */
    private final static Pattern PATTERN_FLOAT = Pattern.compile(
            "[-+]?([0-9][0-9_]*)?\\.[0-9]*([eE][-+][0-9]+)?");
    
    /*
    /**********************************************************************
    /* Configuration
    /**********************************************************************
     */
    
    /**
     * Codec used for data binding when (if) requested.
     */
    protected ObjectCodec _objectCodec;

    protected int _yamlFeatures;

    /*
    /**********************************************************************
    /* Input sources
    /**********************************************************************
     */

    /**
     * Need to keep track of underlying {@link Reader} to be able to
     * auto-close it (if required to)
     */
    protected final Reader _reader;

    protected final Tokenizer _tokenizer;

    /*
    /**********************************************************************
    /* State
    /**********************************************************************
     */

    protected String _lastTag;
    
    /**
     * We need to keep track of text values.
     */
    protected String _textValue;

    /**
     * Let's also have a local copy of the current field name
     */
    protected String _currentFieldName;

    /**
     * Flag that is set when current token was derived from an Alias
     * (reference to another value's anchor)
     * 
     * @since 2.1
     */
    protected boolean _currentIsAlias;

    /**
     * Anchor for the value that parser currently points to: in case of
     * structured types, value whose first token current token is.
     */
    protected String _currentAnchor;

    /**
     * Looks like we need to materialize matching end-array (and maybe object?)
     * tokens to compensate for implicitly injected start-array tokens.
     */
    protected Token _pendingToken;
    
    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
     */
    
    public YAMLParser(IOContext ctxt, BufferRecycler br,
            int parserFeatures, int csvFeatures,
            ObjectCodec codec, Reader reader)
    {
        super(ctxt, parserFeatures);    
        _objectCodec = codec;
        _yamlFeatures = csvFeatures;
        _reader = reader;
        _tokenizer = new Tokenizer(reader);

        // simplify a bit by skipping fluff
        TokenType t = _tokenizer.peekNextTokenType();
        while ((t == TokenType.DOCUMENT_START)
                    || (t == TokenType.STREAM_START)) {
            _tokenizer.getNextToken();
            t = _tokenizer.peekNextTokenType();
        }
    }

    @Override
    public ObjectCodec getCodec() {
        return _objectCodec;
    }

    @Override
    public void setCodec(ObjectCodec c) {
        _objectCodec = c;
    }

    /*                                                                                       
    /**********************************************************                              
    /* Extended YAML-specific API
    /**********************************************************                              
     */

    /**
     * Method that can be used to check whether current token was
     * created from YAML Alias token (reference to an anchor).
     * 
     * @since 2.1
     */
    public boolean isCurrentAlias() {
        return _currentIsAlias;
    }

    /**
     * Method that can be used to check if the current token has an
     * associated anchor (id to reference via Alias)
     * 
     * @deprecated Since 2.3 (was added in 2.1) -- use {@link #getObjectId} instead
     */
    @Deprecated
    public String getCurrentAnchor() {
        return _currentAnchor;
    }
    
    /*                                                                                       
    /**********************************************************                              
    /* Versioned                                                                             
    /**********************************************************                              
     */

    @Override
    public Version version() {
        return PackageVersion.VERSION;
    }

    /*
    /**********************************************************                              
    /* ParserBase method impls
    /**********************************************************                              
     */

    @Override
    protected boolean loadMore() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void _finishString() throws IOException, JsonParseException {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void _closeInput() throws IOException {
        _reader.close();
    }
    
    /*
    /**********************************************************                              
    /* Overridden methods
    /**********************************************************                              
     */
    
    /*
    /***************************************************
    /* Public API, configuration
    /***************************************************
     */

    /**
     * Method for enabling specified CSV feature
     * (check {@link Feature} for list of features)
     */
    public JsonParser enable(YAMLParser.Feature f)
    {
        _yamlFeatures |= f.getMask();
        return this;
    }

    /**
     * Method for disabling specified  CSV feature
     * (check {@link Feature} for list of features)
     */
    public JsonParser disable(YAMLParser.Feature f)
    {
        _yamlFeatures &= ~f.getMask();
        return this;
    }

    /**
     * Method for enabling or disabling specified CSV feature
     * (check {@link Feature} for list of features)
     */
    public JsonParser configure(YAMLParser.Feature f, boolean state)
    {
        if (state) {
            enable(f);
        } else {
            disable(f);
        }
        return this;
    }

    /**
     * Method for checking whether specified CSV {@link Feature}
     * is enabled.
     */
    public boolean isEnabled(YAMLParser.Feature f) {
        return (_yamlFeatures & f.getMask()) != 0;
    }

//    @Override public CsvSchema getSchema() 
    
    /*
    /**********************************************************
    /* Location info
    /**********************************************************
     */

    @Override
    public JsonLocation getTokenLocation()
    {
        // !!! TODO: retain token location
        return getCurrentLocation();
    }

    @Override
    public JsonLocation getCurrentLocation() {
        return new JsonLocation(_ioContext.getSourceReference(),
                -1,
                _tokenizer.getLineNumber() + 1, // from 0- to 1-based
                _tokenizer.getColumn() + 1); // ditto
    }

    // Note: SHOULD override 'getTokenLineNr', 'getTokenColumnNr', but those are final in 2.0
    
    /*
    /**********************************************************
    /* Parsing
    /**********************************************************
     */

    protected Token _nextToken() {
        Token t = _tokenizer.getNextToken();
        TokenType type = t.type;
        if (type == TokenType.ANCHOR || type == TokenType.TAG) {
            return _nextToken2(t);
        }
        return t;
    }

    private Token _nextToken2(Token t)
    {
        do {
            TokenType type = t.type;
            if (type == TokenType.ANCHOR) {
                // I assume anchors are fine to be found about anywhere, so:
                _currentAnchor = ((AnchorToken) t).getInstanceName();
            } else if (type == TokenType.TAG) {
                // handle vs suffix?
                _lastTag = ((TagToken) t).getSuffix();
            } else {
                break;
            }
            t = _tokenizer.getNextToken();
        } while (t != null);
        return t;
    }
    
    @Override
    public JsonToken nextToken() throws IOException
    /*
    {
        JsonToken t = nextToken0();
        System.out.println("JSON: "+t);
        return t;
    }

    JsonToken nextToken0() throws IOException
    */
    {
        _currentIsAlias = false;
        _binaryValue = null;
        _currentAnchor = null;

        if (_closed) {
            return null;
        }

        _lastTag = null;

        Token t = _pendingToken;
        if (t == null) {
            t = _nextToken();
            // is null ok? Assume it is, for now, consider to be same as end-of-doc
            if (t == null) {
                // TODO: verify it's fine
                close();
                return (_currToken = null);
            }
        } else {
            _pendingToken = null;
        }
        TokenType type = t.type;

        /* One complication: field names are only inferred from the
         * fact that we are in Object context...
         */
        if (_parsingContext.inObject() && _currToken != JsonToken.FIELD_NAME) {
            if (type == TokenType.KEY) {
                t = _nextToken();
                type = t.type;
            } else {
                // end is fine
                if (type == TokenType.BLOCK_END || type == TokenType.FLOW_MAPPING_END) {
                    if (!_parsingContext.inObject()) { // sanity check is optional, but let's do it for now
                        _reportMismatchedEndMarker('}', ']');
                    }
                    _parsingContext = _parsingContext.getParent();
                    return (_currToken = JsonToken.END_OBJECT);
                }
            }
            if (t.type != TokenType.SCALAR) {
                _reportError("Expected a field name (Scalar value in YAML), got this instead: "+type);
            }
            ScalarToken scalar = (ScalarToken) t;
            String name = scalar.getValue();
            _currentFieldName = name;
            _parsingContext.setCurrentName(name);
            return (_currToken = JsonToken.FIELD_NAME);
        }
        // why do we get useless VALUE tokens?
        boolean gotValue = (type == TokenType.VALUE);
        if (gotValue) {
            t = _nextToken();
            type = t.type;
        }

        // Looks like these entry markers indicate 'implicit' arrays?
        if (type == TokenType.BLOCK_ENTRY || type == TokenType.FLOW_ENTRY) {
            if (!_parsingContext.inArray()) {
                _parsingContext = _parsingContext.createChildArrayContext(_tokenizer.getLineNumber(),
                        _tokenizer.getColumn());
                return (_currToken = JsonToken.START_ARRAY);
            }
            t = _nextToken();
            type = t.type;
        }

        switch (type) {
        case KEY: // should only get this due to missing END_ARRAY so:
            if (_parsingContext.inArray()) {
                _pendingToken = _tokenizer.getNextToken();
                _parsingContext = _parsingContext.getParent();
                return (_currToken = JsonToken.END_ARRAY);
            }
            // otherwise problem
            break;
        case SCALAR:
            JsonToken jt = _decodeScalar((ScalarToken) t, _lastTag);
            _currToken = jt;
            return jt;
        case BLOCK_MAPPING_START:
        case FLOW_MAPPING_START:
            _parsingContext = _parsingContext.createChildObjectContext(_tokenizer.getLineNumber(),
                    _tokenizer.getColumn());
            return (_currToken = JsonToken.START_OBJECT);
        case BLOCK_END:
        case FLOW_MAPPING_END:
            // Work-around for implicit START-ARRAY
            if (_parsingContext.inArray()) {
                _pendingToken = t;
                return (_currToken = JsonToken.END_ARRAY);
            }
            _reportError("Not expecting END_OBJECT but a value");
        case BLOCK_SEQUENCE_START:
        case FLOW_SEQUENCE_START:
            _parsingContext = _parsingContext.createChildArrayContext(_tokenizer.getLineNumber(),
                    _tokenizer.getColumn());
            return (_currToken = JsonToken.START_ARRAY);
        case FLOW_SEQUENCE_END:
            if (!_parsingContext.inArray()) { // sanity check is optional, but let's do it for now
                _reportMismatchedEndMarker(']', '}');
            }
            _parsingContext = _parsingContext.getParent();
            return (_currToken = JsonToken.END_ARRAY);

        case ALIAS:
            _currentIsAlias = true;
            _textValue = ((AliasToken) t).getInstanceName();
            // for now, nothing to do: in future, maybe try to expose as ObjectIds?
            return (_currToken = JsonToken.VALUE_STRING);
        case STREAM_END:
            close();
            return (_currToken = null);

            // These should have been processed already:
        case ANCHOR:
        case TAG:

            // Not sure what to do with these however
        case DOCUMENT_START: // should have been skipped already
        case STREAM_START: // ditto
        case DOCUMENT_END: // logical end
            // but if these encountered, just skip with bit of recursion; they could
            // occur if stream contains 
            return nextToken();

        case BLOCK_ENTRY:
        case FLOW_ENTRY:
        case DIRECTIVE:
        default:
        }
        _reportError("Unexpected token type; expected a value, tag or anchor, got: "+type.name());
        return null; // never gets here
    }
    
    protected JsonToken _decodeScalar(ScalarToken scalar, String typeTag)
    {
        String value = scalar.getValue();
        _textValue = value;
        final int len = value.length();

        if (typeTag == null) { // no, implicit
            // We only try to parse the string value if it is in the plain flow style.
            // The API for ScalarEvent.getStyle() might be read as a null being returned
            // in the plain flow style, but debugging shows the null-byte character, so
            // we support both.
            Character style = scalar.getStyle();

            if ((style == null || style == '\u0000') && len > 0) {
                char c = value.charAt(0);
                switch (c) {
                case 'n':
                    if ("null".equals(value)) {
                        return JsonToken.VALUE_NULL;
                    }
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                case '+':
                case '-':
                case '.':
                    JsonToken t = _decodeNumberScalar(value, len);
                    if (t != null) {
                        return t;
                    }
                }
                Boolean B = _matchYAMLBoolean(value, len);
                if (B != null) {
                    return B.booleanValue() ? JsonToken.VALUE_TRUE : JsonToken.VALUE_FALSE;
                }
            }
        } else { // yes, got type tag
            // canonical values by YAML are actually 'y' and 'n'; but plenty more unofficial:
            if ("bool".equals(typeTag)) { // must be "true" or "false"
                Boolean B = _matchYAMLBoolean(value, len);
                if (B != null) {
                    return B.booleanValue() ? JsonToken.VALUE_TRUE : JsonToken.VALUE_FALSE;
                }
            } else if ("int".equals(typeTag)) {
                return JsonToken.VALUE_NUMBER_INT;
            } else if ("float".equals(typeTag)) {
                return JsonToken.VALUE_NUMBER_FLOAT;
            } else if ("null".equals(typeTag)) {
                return JsonToken.VALUE_NULL;
            }
        }
        
        // any way to figure out actual type? No?
        return (_currToken = JsonToken.VALUE_STRING);
    }

    protected Boolean _matchYAMLBoolean(String value, int len)
    {
        switch (len) {
        case 1:
            switch (value.charAt(0)) {
            case 'y': case 'Y': return Boolean.TRUE;
            case 'n': case 'N': return Boolean.FALSE;
            }
            break;
        case 2:
            if ("no".equalsIgnoreCase(value)) return Boolean.FALSE;
            if ("on".equalsIgnoreCase(value)) return Boolean.TRUE;
            break;
        case 3:
            if ("yes".equalsIgnoreCase(value)) return Boolean.TRUE;
            if ("off".equalsIgnoreCase(value)) return Boolean.FALSE;
            break;
        case 4:
            if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
            break;
        case 5:
            if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
            break;
        }
        return null;
    }

    protected JsonToken _decodeNumberScalar(String value, final int len)
    {
        if ("0".equals(value)) { // special case for regexp (can't take minus etc)
            _numberNegative = false;
            _numberInt = 0;
            _numTypesValid = NR_INT;
            return JsonToken.VALUE_NUMBER_INT;
        }
        /* 05-May-2012, tatu: Turns out this is a hot spot; so let's write it
         *   out and avoid regexp overhead...
         */
        //if (PATTERN_INT.matcher(value).matches()) {
        int i;
        if (value.charAt(0) == '-') {
            _numberNegative = true;
            i = 1;
            if (len == 1) {
                return null;
            }
        } else {
            _numberNegative = false;
            i = 0;
        }
        while (true) {
            int c = value.charAt(i);
            if (c > '9' || c < '0') {
                break;
            }
            if (++i == len) {
                _numTypesValid = 0;
                return JsonToken.VALUE_NUMBER_INT;
            }
        }
        if (PATTERN_FLOAT.matcher(value).matches()) {
            _numTypesValid = 0;
            return JsonToken.VALUE_NUMBER_FLOAT;
        }
        return null;
    }   

    /*
    /**********************************************************
    /* String value handling
    /**********************************************************
     */

    // For now we do not store char[] representation...
    @Override
    public boolean hasTextCharacters() {
        return false;
    }
    
    @Override
    public String getText() throws IOException, JsonParseException
    {
        if (_currToken == JsonToken.VALUE_STRING) {
            return _textValue;
        }
        if (_currToken == JsonToken.FIELD_NAME) {
            return _currentFieldName;
        }
        if (_currToken != null) {
            if (_currToken.isScalarValue()) {
                return _textValue;
            }
            return _currToken.asString();
        }
        return null;
    }

    @Override
    public String getCurrentName() throws IOException, JsonParseException
    {
        if (_currToken == JsonToken.FIELD_NAME) {
            return _currentFieldName;
        }
        return super.getCurrentName();
    }
    
    @Override
    public char[] getTextCharacters() throws IOException, JsonParseException {
        String text = getText();
        return (text == null) ? null : text.toCharArray();
    }

    @Override
    public int getTextLength() throws IOException, JsonParseException {
        String text = getText();
        return (text == null) ? 0 : text.length();
    }

    @Override
    public int getTextOffset() throws IOException, JsonParseException {
        return 0;
    }
    
    /*
    /**********************************************************************
    /* Binary (base64)
    /**********************************************************************
     */

    @Override
    public Object getEmbeddedObject() throws IOException, JsonParseException {
        return null;
    }
    
    @SuppressWarnings("resource")
    @Override
    public byte[] getBinaryValue(Base64Variant variant) throws IOException, JsonParseException
    {
        if (_binaryValue == null) {
            if (_currToken != JsonToken.VALUE_STRING) {
                _reportError("Current token ("+_currToken+") not VALUE_STRING, can not access as binary");
            }
            ByteArrayBuilder builder = _getByteArrayBuilder();
            _decodeBase64(getText(), builder, variant);
            _binaryValue = builder.toByteArray();
        }
        return _binaryValue;
    }

    /*
    /**********************************************************************
    /* Number accessor overrides
    /**********************************************************************
     */
    
    @Override
    protected void _parseNumericValue(int expType) throws IOException
    {
        // Int or float?
        if (_currToken == JsonToken.VALUE_NUMBER_INT) {
            int len = _textValue.length();
            if (_numberNegative) {
                len--;
            }
            if (len <= 9) { // definitely fits in int
                _numberInt = Integer.parseInt(_textValue);
                _numTypesValid = NR_INT;
                return;
            }
            if (len <= 18) { // definitely fits AND is easy to parse using 2 int parse calls
                long l = Long.parseLong(_textValue);
                // [JACKSON-230] Could still fit in int, need to check
                if (len == 10) {
                    if (_numberNegative) {
                        if (l >= Integer.MIN_VALUE) {
                            _numberInt = (int) l;
                            _numTypesValid = NR_INT;
                            return;
                        }
                    } else {
                        if (l <= Integer.MAX_VALUE) {
                            _numberInt = (int) l;
                            _numTypesValid = NR_INT;
                            return;
                        }
                    }
                }
                _numberLong = l;
                _numTypesValid = NR_LONG;
                return;
            }
            // !!! TODO: implement proper bounds checks; now we'll just use BigInteger for convenience
            try {
                BigInteger n = new BigInteger(_textValue);
                // Could still fit in a long, need to check
                if (len == 19 && n.bitLength() <= 63) {
                    _numberLong = n.longValue();
                    _numTypesValid = NR_LONG;
                    return;
                }
                _numberBigInt = n;
                _numTypesValid = NR_BIGINT;
                return;
            } catch (NumberFormatException nex) {
                // Can this ever occur? Due to overflow, maybe?
                _wrapError("Malformed numeric value '"+_textValue+"'", nex);
            }
        }
        if (_currToken == JsonToken.VALUE_NUMBER_FLOAT) {
            // related to [Issue-4]: strip out optional underscores, if any:
            String str = _cleanYamlDouble(_textValue);
            try {
                if (expType == NR_BIGDECIMAL) {
                    _numberBigDecimal = new BigDecimal(str);
                    _numTypesValid = NR_BIGDECIMAL;
                } else {
                    // Otherwise double has to do
                    _numberDouble = Double.parseDouble(str);
                    _numTypesValid = NR_DOUBLE;
                }
            } catch (NumberFormatException nex) {
                // Can this ever occur? Due to overflow, maybe?
                _wrapError("Malformed numeric value '"+str+"'", nex);
            }
            return;
        }
        _reportError("Current token ("+_currToken+") not numeric, can not use numeric value accessors");
    }

    @Override
    protected int _parseIntValue() throws IOException
    {
        if (_currToken == JsonToken.VALUE_NUMBER_INT) {
            int len = _textValue.length();
            if (_numberNegative) {
                len--;
            }
            if (len <= 9) { // definitely fits in int
                _numTypesValid = NR_INT;
                return (_numberInt = Integer.parseInt(_textValue));
            }
        }
        _parseNumericValue(NR_INT);
        if ((_numTypesValid & NR_INT) == 0) {
            convertNumberToInt();
        }
        return _numberInt;
    }

    /*
    /**********************************************************************
    /* Native id (type id) access
    /**********************************************************************
     */

    @Override
    public boolean canReadObjectId() { // yup
        return true;
    }
    
    @Override
    public boolean canReadTypeId() {
        return true; // yes, YAML got 'em
    }
    
    @Override
    public String getObjectId() throws IOException, JsonGenerationException
    {
        return _currentAnchor;
    }

    @Override
    public String getTypeId() throws IOException, JsonGenerationException
    {
        String tag = _lastTag;
        if (tag != null) {
            /* 04-Aug-2013, tatu: Looks like YAML parser's expose these in...
             *   somewhat exotic ways sometimes. So let's prepare to peel off
             *   some wrappings:
             */
            while (tag.startsWith("!")) {
                tag = tag.substring(1);
            }
            return tag;
        }
        return null;
    }
    
    /*
    /**********************************************************************
    /* Internal methods
    /**********************************************************************
     */
    
    /**
     * Helper method used to clean up YAML floating-point value so it can be parsed
     * using standard JDK classes.
     * Currently this just means stripping out optional underscores.
     */
    private String _cleanYamlDouble(String str)
    {
        final int len = str.length();
        int ix = str.indexOf('_');
        if (ix < 0 || len == 0) {
            return str;
        }
        StringBuilder sb = new StringBuilder(len);
        // first: do we have a leading plus sign to skip?
        int i = (str.charAt(0) == '+') ? 1 : 0;
        for (; i < len; ++i) {
            char c = str.charAt(i);
            if (c != '_') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
