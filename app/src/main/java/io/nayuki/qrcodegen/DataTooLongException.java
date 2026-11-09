/* 
 * QR Code generator library (Java)
 * 
 * Copyright (c) Project Nayuki (MIT License).
 * https://www.nayuki.io/page/qr-code-generator-library
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 * - The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * - The Software is provided "as is", without warranty of any kind, express or
 * implied, including but not limited to the warranties of merchantability,
 * fitness for a particular purpose and noninfringement.
 */

package io.nayuki.qrcodegen;

/**
 * Thrown when the supplied data does not fit in any allowed QR Code version.
 */
public class DataTooLongException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	public DataTooLongException() {
		super();
	}

	public DataTooLongException(String message) {
		super(message);
	}
}
