/**
 * Copyright 2003-2016 SSHTOOLS Limited. All Rights Reserved.
 *
 * For product documentation visit https://www.sshtools.com/
 *
 * This file is part of J2SSH Maverick.
 *
 * J2SSH Maverick is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * J2SSH Maverick is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with J2SSH Maverick.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.sshtools.ssh.components;

import java.io.IOException;

/**
 * 
 * <p>
 * Base class for all SSH protocol ciphers. The cipher itself has 2 modes,
 * encryption or decrpytion. The same method is used to transporm the data
 * depending upon the mode.
 * </p>
 * 
 * @author Lee David Painter
 */
public abstract class SshCipher {

	String algorithm;

	public SshCipher(String algorithm) {
		this.algorithm = algorithm;
	}

	public String getAlgorithm() {
		return algorithm;
	}

	/**
	 * Encryption mode.
	 */
	public static final int ENCRYPT_MODE = 0;

	/**
	 * Decryption mode.
	 */
	public static final int DECRYPT_MODE = 1;

	/**
	 * Get the cipher block size.
	 * 
	 * @return the block size in bytes.
	 */
	public abstract int getBlockSize();

	/**
	 * Initialize the cipher with up to 40 bytes of iv and key data. Each
	 * implementation should take as much data from the initialization as it
	 * needs ignoring any data that it does not require.
	 * 
	 * @param mode
	 *            the mode to operate
	 * @param iv
	 *            the initiaization vector
	 * @param keydata
	 *            the key data
	 * @throws IOException
	 */
	public abstract void init(int mode, byte[] iv, byte[] keydata)
			throws IOException;

	/**
	 * Transform the byte array according to the cipher mode.
	 * 
	 * @param data
	 * @throws IOException
	 */
	public void transform(byte[] data) throws IOException {
		transform(data, 0, data, 0, data.length);
	}

	/**
	 * Transform the byte array according to the cipher mode; it is legal for
	 * the source and destination arrays to reference the same physical array so
	 * care should be taken in the transformation process to safeguard this
	 * rule.
	 * 
	 * @param src
	 * @param start
	 * @param dest
	 * @param offset
	 * @param len
	 * @throws IOException
	 */
	public abstract void transform(byte[] src, int start, byte[] dest,
			int offset, int len) throws IOException;
}
