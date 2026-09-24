package com.sivrad.core.llm;
public interface TokenSink { boolean onPiece(byte[] utf8); }
