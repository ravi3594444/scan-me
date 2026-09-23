-- object table device
CREATE TABLE device (
  -- hex(SHA-256(identity_pk)[0..16]): 32 lower-case hex digits. For a browser peer, a random id of the same form.
  id TEXT NOT NULL PRIMARY KEY CHECK (length(id) = 32 AND id NOT GLOB '*[^0-9a-f]*'),
  -- Ed25519 public key (32 bytes); null only for a browser peer, which has no identity.
  identity_pk BLOB UNIQUE CHECK (identity_pk IS NULL OR length(identity_pk) = 32),
  -- The name the peer announces (sanitised, at most 64 UTF-8 bytes); updated at every handshake (F-B4).
  nickname TEXT NOT NULL,
  -- The user's own name for the device (Devices tab rename); shown instead of nickname when set.
  custom_name TEXT,
  platform TEXT NOT NULL CHECK (platform IN ('phone', 'laptop', 'desktop', 'browser', 'unknown')),
  trusted INTEGER NOT NULL DEFAULT 0 CHECK (trusted IN (0, 1)),
  auto_accept INTEGER NOT NULL DEFAULT 0 CHECK (auto_accept IN (0, 1)),
  -- Per-pair HKDF "recog" value for the Hello proof, sealed by the SecretFieldCipher; null when untrusted.
  recognition_secret BLOB,
  -- S3: the peer's advertising secret k_adv from TrustShare (sealed), its generation, and the generation before it,
  -- so trusted beacons resolve across a rotation; all null when untrusted.
  peer_adv_secret BLOB,
  peer_adv_generation INTEGER CHECK (peer_adv_generation IS NULL OR peer_adv_generation >= 0),
  previous_peer_adv_secret BLOB,
  -- Until when the previous generation still resolves (a short grace period after the rotation was stored), so the
  -- device the peer forgot, which knows that k_adv, cannot keep passing for the peer on this radar.
  previous_peer_adv_until INTEGER,
  -- Bluetooth Classic address (48-bit value) of a trusted desktop, which stops advertising it in Trusted-only mode.
  classic_address INTEGER CHECK (classic_address IS NULL OR classic_address BETWEEN 1 AND 281474976710654),
  first_seen INTEGER NOT NULL,
  last_seen INTEGER NOT NULL,
  trusted_at INTEGER,
  CHECK (platform = 'browser' OR identity_pk IS NOT NULL),
  CHECK (identity_pk IS NOT NULL OR trusted = 0),
  CHECK (trusted = 1 OR (auto_accept = 0 AND recognition_secret IS NULL AND peer_adv_secret IS NULL
    AND peer_adv_generation IS NULL AND previous_peer_adv_secret IS NULL AND previous_peer_adv_until IS NULL
    AND classic_address IS NULL AND trusted_at IS NULL)),
  CHECK (trusted = 0 OR (recognition_secret IS NOT NULL AND trusted_at IS NOT NULL)),
  CHECK ((peer_adv_secret IS NULL) = (peer_adv_generation IS NULL)),
  CHECK (previous_peer_adv_secret IS NULL OR peer_adv_secret IS NOT NULL),
  CHECK ((previous_peer_adv_secret IS NULL) = (previous_peer_adv_until IS NULL))
)

-- object index device_by_trust
CREATE INDEX device_by_trust ON device(trusted, last_seen)

-- object table transfer
CREATE TABLE transfer (
  -- hex(transfer_id): 32 lower-case hex digits.
  id TEXT NOT NULL PRIMARY KEY CHECK (length(id) = 32 AND id NOT GLOB '*[^0-9a-f]*'),
  peer_device_id TEXT NOT NULL REFERENCES device(id),
  direction TEXT NOT NULL CHECK (direction IN ('send', 'receive')),
  -- LinkKind wire names; the link data last flowed over (the badge).
  transport TEXT CHECK (transport IN ('lan', 'p2p', 'hotspot', 'bluetooth')),
  band TEXT CHECK (band IN ('2.4', '5', '6')),
  started_at INTEGER NOT NULL,
  -- Set exactly when the status is terminal.
  finished_at INTEGER,
  -- Last activity: every state, progress, link or hint change; the 24 h clean-up measures from here (§7.6).
  updated_at INTEGER NOT NULL,
  bytes_total INTEGER NOT NULL CHECK (bytes_total >= 0),
  bytes_done INTEGER NOT NULL DEFAULT 0 CHECK (bytes_done >= 0 AND bytes_done <= bytes_total),
  avg_speed_bps INTEGER CHECK (avg_speed_bps IS NULL OR avg_speed_bps >= 0),
  status TEXT NOT NULL CHECK (status IN ('offered', 'accepted', 'streaming', 'interrupted', 'verifying', 'done',
    'failed', 'cancelled')),
  -- HintCode wire names, comma-separated, in the order they first fired.
  hint_codes TEXT,
  -- From the Offer summary (N12), so History can say "12 photos · 48 MB" even for a declined offer.
  file_count INTEGER NOT NULL CHECK (file_count >= 1),
  mime_histogram TEXT,
  -- Files that failed verification (Complete.status = partial when some did and others arrived).
  failed_files INTEGER NOT NULL DEFAULT 0 CHECK (failed_files >= 0 AND failed_files <= file_count),
  CHECK ((finished_at IS NULL) = (status NOT IN ('done', 'failed', 'cancelled')))
)

-- object index transfer_by_start
CREATE INDEX transfer_by_start ON transfer(started_at, id)

-- object index transfer_by_status
CREATE INDEX transfer_by_status ON transfer(status, started_at)

-- object index transfer_by_peer
CREATE INDEX transfer_by_peer ON transfer(peer_device_id, started_at)

-- object table transfer_file
CREATE TABLE transfer_file (
  transfer_id TEXT NOT NULL REFERENCES transfer(id) ON DELETE CASCADE,
  file_index INTEGER NOT NULL CHECK (file_index >= 0),
  name TEXT NOT NULL,
  mime_type TEXT,
  size INTEGER NOT NULL CHECK (size >= 0),
  -- Whole-file SHA-256; null until FileDone (S2).
  sha256 BLOB CHECK (sha256 IS NULL OR length(sha256) = 32),
  -- The file's URI on this device: where a received file was published, or the source a sent file was read from.
  saved_uri TEXT,
  status TEXT NOT NULL CHECK (status IN ('pending', 'in_progress', 'done', 'failed', 'cancelled')),
  PRIMARY KEY (transfer_id, file_index)
)

-- object table chunk_manifest
CREATE TABLE chunk_manifest (
  transfer_id TEXT NOT NULL REFERENCES transfer(id) ON DELETE CASCADE,
  file_index INTEGER NOT NULL CHECK (file_index >= -1),
  unit_count INTEGER NOT NULL CHECK (unit_count >= 0),
  received_bitmap BLOB NOT NULL CHECK (length(received_bitmap) = (unit_count + 7) / 8),
  chunk_hashes BLOB NOT NULL CHECK (length(chunk_hashes) = unit_count * 16),
  partial_unit INTEGER CHECK (partial_unit IS NULL OR (partial_unit >= 0 AND partial_unit < unit_count)),
  partial_bytes INTEGER NOT NULL DEFAULT 0 CHECK (partial_bytes >= 0),
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (transfer_id, file_index),
  CHECK ((partial_unit IS NULL) = (partial_bytes = 0))
)

-- object table settings
CREATE TABLE settings (
  key TEXT NOT NULL PRIMARY KEY,
  value TEXT NOT NULL
)

-- object view transfer_row
CREATE VIEW transfer_row AS
SELECT
  transfer.id,
  transfer.peer_device_id,
  transfer.direction,
  transfer.transport,
  transfer.band,
  transfer.started_at,
  transfer.finished_at,
  transfer.updated_at,
  transfer.bytes_total,
  transfer.bytes_done,
  transfer.avg_speed_bps,
  transfer.status,
  transfer.hint_codes,
  transfer.file_count,
  transfer.mime_histogram,
  transfer.failed_files,
  device.nickname AS peer_nickname,
  device.custom_name AS peer_custom_name,
  device.platform AS peer_platform
FROM transfer
JOIN device ON device.id = transfer.peer_device_id

