package commsplane

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
)

// sealer encrypts queue payloads and contact addresses (AES-256-GCM) and computes the
// blind index used to look addresses up without decrypting them (comms-design §9).
// Both keys are derived from one data key that is separate from the KYC and MFA keys.
type sealer struct {
	aead     cipher.AEAD
	indexKey []byte
}

func loadSealer(path string) (*sealer, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("comms: data key: %w", err)
	}
	material := []byte(strings.TrimSpace(string(raw)))
	if dec, err := hex.DecodeString(string(material)); err == nil {
		material = dec
	}
	if len(material) < 32 {
		return nil, errors.New("comms: data key must hold at least 32 bytes")
	}
	return newSealer(material)
}

func newSealer(material []byte) (*sealer, error) {
	derive := func(label string) []byte {
		m := hmac.New(sha256.New, material)
		m.Write([]byte(label))
		return m.Sum(nil)
	}
	block, err := aes.NewCipher(derive("comms/aes-gcm/v1"))
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return &sealer{aead: aead, indexKey: derive("comms/blind-index/v1")}, nil
}

func (s *sealer) seal(plain []byte) ([]byte, error) {
	nonce := make([]byte, s.aead.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}
	return s.aead.Seal(nonce, nonce, plain, nil), nil
}

func (s *sealer) open(ct []byte) ([]byte, error) {
	n := s.aead.NonceSize()
	if len(ct) < n {
		return nil, errors.New("comms: ciphertext too short")
	}
	return s.aead.Open(nil, ct[:n], ct[n:], nil)
}

func (s *sealer) sealJSON(v any) ([]byte, error) {
	b, err := json.Marshal(v)
	if err != nil {
		return nil, err
	}
	return s.seal(b)
}

func (s *sealer) openJSON(ct []byte, v any) error {
	b, err := s.open(ct)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, v)
}

// blindIndex is stable per normalised address.
func (s *sealer) blindIndex(channel, address string) string {
	m := hmac.New(sha256.New, s.indexKey)
	m.Write([]byte(channel + ":" + address))
	return hex.EncodeToString(m.Sum(nil))
}

// mask keeps the first and last characters: "+264 81 *** 4321", "j***@example.com".
func mask(channel, address string) string {
	switch channel {
	case "email":
		at := strings.LastIndex(address, "@")
		if at <= 0 {
			return "***"
		}
		return address[:1] + "***" + address[at:]
	default:
		if len(address) <= 6 {
			return "***"
		}
		return address[:4] + "***" + address[len(address)-4:]
	}
}
