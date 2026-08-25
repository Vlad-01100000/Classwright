package com.classwright.core;

/**
 * JVM instruction opcodes, as defined by JVMS chapter 6.
 *
 * <p>Only the instructions this engine emits are listed. The set is deliberately smaller than the
 * full instruction set: Classwright generates glue code &mdash; field access, argument shuffling,
 * casts, calls, and returns &mdash; and never needs most of the arithmetic or synchronisation
 * opcodes a compiler would.
 *
 * <p>Values are fixed by the specification and can never change, which is one small corner of this
 * project where hard-coded constants are safe forever.
 */
final class Opcodes {

    private Opcodes() {
    }

    // -- constants ---------------------------------------------------------------------------
    public static final int NOP = 0;
    public static final int ACONST_NULL = 1;
    public static final int ICONST_M1 = 2;
    public static final int ICONST_0 = 3;
    public static final int ICONST_1 = 4;
    public static final int ICONST_5 = 8;
    public static final int LCONST_0 = 9;
    public static final int FCONST_0 = 11;
    public static final int DCONST_0 = 14;
    public static final int BIPUSH = 16;
    public static final int SIPUSH = 17;
    public static final int LDC = 18;
    public static final int LDC_W = 19;
    public static final int LDC2_W = 20;

    // -- loads -------------------------------------------------------------------------------
    public static final int ILOAD = 21;
    public static final int LLOAD = 22;
    public static final int FLOAD = 23;
    public static final int DLOAD = 24;
    public static final int ALOAD = 25;
    /** {@code iload_0}; the four single-byte forms of each load run consecutively from here. */
    public static final int ILOAD_0 = 26;
    public static final int LLOAD_0 = 30;
    public static final int FLOAD_0 = 34;
    public static final int DLOAD_0 = 38;
    public static final int ALOAD_0 = 42;

    // -- array loads -------------------------------------------------------------------------
    public static final int IALOAD = 46;
    public static final int LALOAD = 47;
    public static final int FALOAD = 48;
    public static final int DALOAD = 49;
    public static final int AALOAD = 50;
    public static final int BALOAD = 51;
    public static final int CALOAD = 52;
    public static final int SALOAD = 53;

    // -- stores ------------------------------------------------------------------------------
    public static final int ISTORE = 54;
    public static final int LSTORE = 55;
    public static final int FSTORE = 56;
    public static final int DSTORE = 57;
    public static final int ASTORE = 58;
    public static final int ISTORE_0 = 59;
    public static final int LSTORE_0 = 63;
    public static final int FSTORE_0 = 67;
    public static final int DSTORE_0 = 71;
    public static final int ASTORE_0 = 75;

    // -- array stores ------------------------------------------------------------------------
    public static final int IASTORE = 79;
    public static final int LASTORE = 80;
    public static final int FASTORE = 81;
    public static final int DASTORE = 82;
    public static final int AASTORE = 83;
    public static final int BASTORE = 84;
    public static final int CASTORE = 85;
    public static final int SASTORE = 86;

    // -- stack -------------------------------------------------------------------------------
    public static final int POP = 87;
    public static final int POP2 = 88;
    public static final int DUP = 89;
    public static final int DUP_X1 = 90;
    public static final int DUP_X2 = 91;
    public static final int DUP2 = 92;
    public static final int DUP2_X1 = 93;
    public static final int DUP2_X2 = 94;
    public static final int SWAP = 95;

    // -- arithmetic (only what argument marshalling needs) -----------------------------------
    public static final int IADD = 96;
    public static final int LADD = 97;
    public static final int FADD = 98;
    public static final int DADD = 99;
    public static final int ISUB = 100;
    public static final int LSUB = 101;
    public static final int FSUB = 102;
    public static final int DSUB = 103;
    /** Unsigned right shift on int; how chunked dispatch turns an index into a chunk number. */
    public static final int IUSHR = 124;

    // -- conversions -------------------------------------------------------------------------
    public static final int I2L = 133;
    public static final int I2F = 134;
    public static final int I2D = 135;
    public static final int L2I = 136;
    public static final int L2F = 137;
    public static final int L2D = 138;
    public static final int F2I = 139;
    public static final int F2L = 140;
    public static final int F2D = 141;
    public static final int D2I = 142;
    public static final int D2L = 143;
    public static final int D2F = 144;
    public static final int I2B = 145;
    public static final int I2C = 146;
    public static final int I2S = 147;

    // -- comparisons and branches --------------------------------------------------------------
    public static final int LCMP = 148;
    public static final int FCMPL = 149;
    public static final int FCMPG = 150;
    public static final int DCMPL = 151;
    public static final int DCMPG = 152;
    public static final int IFEQ = 153;
    public static final int IFNE = 154;
    public static final int IFLT = 155;
    public static final int IFGE = 156;
    public static final int IFGT = 157;
    public static final int IFLE = 158;
    public static final int IF_ICMPEQ = 159;
    public static final int IF_ICMPNE = 160;
    public static final int IF_ICMPLT = 161;
    public static final int IF_ICMPGE = 162;
    public static final int IF_ICMPGT = 163;
    public static final int IF_ICMPLE = 164;
    public static final int IF_ACMPEQ = 165;
    public static final int IF_ACMPNE = 166;
    public static final int GOTO = 167;
    public static final int TABLESWITCH = 170;
    public static final int LOOKUPSWITCH = 171;

    // -- returns -----------------------------------------------------------------------------
    public static final int IRETURN = 172;
    public static final int LRETURN = 173;
    public static final int FRETURN = 174;
    public static final int DRETURN = 175;
    public static final int ARETURN = 176;
    public static final int RETURN = 177;

    // -- fields and methods --------------------------------------------------------------------
    public static final int GETSTATIC = 178;
    public static final int PUTSTATIC = 179;
    public static final int GETFIELD = 180;
    public static final int PUTFIELD = 181;
    public static final int INVOKEVIRTUAL = 182;
    public static final int INVOKESPECIAL = 183;
    public static final int INVOKESTATIC = 184;
    public static final int INVOKEINTERFACE = 185;
    public static final int INVOKEDYNAMIC = 186;

    // -- objects and arrays ----------------------------------------------------------------------
    public static final int NEW = 187;
    public static final int NEWARRAY = 188;
    public static final int ANEWARRAY = 189;
    public static final int ARRAYLENGTH = 190;
    public static final int ATHROW = 191;
    public static final int CHECKCAST = 192;
    public static final int INSTANCEOF = 193;
    public static final int MONITORENTER = 194;
    public static final int MONITOREXIT = 195;
    public static final int WIDE = 196;
    public static final int MULTIANEWARRAY = 197;
    public static final int IFNULL = 198;
    public static final int IFNONNULL = 199;
    public static final int GOTO_W = 200;

    // -- newarray element type codes (JVMS 6.5 newarray) ----------------------------------------
    public static final int T_BOOLEAN = 4;
    public static final int T_CHAR = 5;
    public static final int T_FLOAT = 6;
    public static final int T_DOUBLE = 7;
    public static final int T_BYTE = 8;
    public static final int T_SHORT = 9;
    public static final int T_INT = 10;
    public static final int T_LONG = 11;
}
