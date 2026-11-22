package dev.easyeconomy.pshop.tx;

// A durable write failed mid-operation. The operation must abort and
// unwind, never continue as if the write landed.
public final class TxException extends Exception {
	public TxException(String message, Throwable cause) {
		super(message, cause);
	}
}
