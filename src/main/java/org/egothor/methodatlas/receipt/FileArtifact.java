// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.receipt;

/**
 * Immutable reference to a file that influenced a scan, captured by path and
 * SHA-256 fingerprint.
 *
 * <p>
 * Package-private because nothing outside the {@code receipt} package needs to
 * construct artefacts directly; {@link ReceiptBuilder} is the sole producer.
 * </p>
 *
 * @param path   absolute path of the file at the moment the scan ran; preserved
 *               as a string so the receipt remains valid after the file is
 *               moved, renamed, or deleted
 * @param sha256 64-character lowercase hexadecimal SHA-256 digest of the file
 *               bytes, computed via
 *               {@link org.egothor.methodatlas.command.ContentHasher#hashFile}
 */
/* default */ record FileArtifact(String path, String sha256) {
}
