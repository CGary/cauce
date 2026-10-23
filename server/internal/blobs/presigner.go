// Package blobs issues presigned PUT URLs for Cloudflare R2 so uploads never
// pass through the server. No download endpoint exists in phase 2.
package blobs

import (
	"context"
	"fmt"
	"net/url"
	"path"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"

	"cauce/server/internal/config"
)

// PresignExpiry is how long an upload URL stays valid.
const PresignExpiry = 15 * time.Minute

// Presigner wraps the S3 client pointed at R2.
type Presigner struct {
	client  *s3.PresignClient
	bucket  string
	expires time.Duration
}

// New returns nil when R2 is not configured; the handler then answers 503.
func New(cfg config.R2) *Presigner {
	if !cfg.Configured() {
		return nil
	}
	endpoint := fmt.Sprintf("https://%s.r2.cloudflarestorage.com", cfg.AccountID)
	awsCfg := aws.Config{
		Region:      "auto",
		Credentials: credentials.NewStaticCredentialsProvider(cfg.AccessKeyID, cfg.SecretAccessKey, ""),
	}
	client := s3.NewFromConfig(awsCfg, func(o *s3.Options) {
		o.BaseEndpoint = aws.String(endpoint)
		o.UsePathStyle = true
	})
	return &Presigner{
		client:  s3.NewPresignClient(client),
		bucket:  cfg.Bucket,
		expires: PresignExpiry,
	}
}

// PresignPut returns a signed PUT URL for key. Keys are forced under
// "<user_id>/" and must be already clean, so a caller cannot escape its prefix.
func (p *Presigner) PresignPut(ctx context.Context, userID, key, contentType string) (string, error) {
	if err := validateKey(userID, key); err != nil {
		return "", err
	}
	if strings.TrimSpace(contentType) == "" {
		return "", fmt.Errorf("content_type is required")
	}
	req, err := p.client.PresignPutObject(ctx, &s3.PutObjectInput{
		Bucket:      aws.String(p.bucket),
		Key:         aws.String(key),
		ContentType: aws.String(contentType),
	}, s3.WithPresignExpires(p.expires))
	if err != nil {
		return "", fmt.Errorf("presign put: %w", err)
	}
	return req.URL, nil
}

func validateKey(userID, key string) error {
	if userID == "" {
		return fmt.Errorf("missing user id")
	}
	if key == "" {
		return fmt.Errorf("key is required")
	}
	if strings.ContainsAny(key, "\\") {
		return fmt.Errorf("key contains a backslash")
	}
	if path.Clean(key) != key {
		return fmt.Errorf("key must be a clean relative path")
	}
	if !strings.HasPrefix(key, userID+"/") || len(key) == len(userID)+1 {
		return fmt.Errorf("key must start with %q", userID+"/")
	}
	if _, err := url.ParseRequestURI("/" + key); err != nil {
		return fmt.Errorf("invalid key: %w", err)
	}
	return nil
}
