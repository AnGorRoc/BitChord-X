package main

import (
	"context"
	"log"
	"net/http"
	"time"

	"github.com/KabirSinghBhatia/BitChord/backend/config"
	"github.com/KabirSinghBhatia/BitChord/backend/feedback"
)

// registerFeedback mounts the feedback API. Tickets go to PostgreSQL when the server has a
// database; without one (a local test server) they are kept in memory and gone on restart.
func registerFeedback(mux *http.ServeMux) error {
	ctx := context.Background()
	var repo feedback.Repo
	if pool := telemetryStore.Pool(); pool != nil {
		repo = feedback.NewPGRepo(pool)
	} else {
		log.Printf("feedback: no DATABASE_URL; tickets are kept in memory only")
		repo = feedback.NewMemRepo()
	}
	if err := repo.Migrate(ctx); err != nil {
		return err
	}
	svc, err := feedback.New(repo, feedback.Options{
		Dir:            config.FeedbackDir,
		MaxDiskBytes:   int64(config.FeedbackMaxDiskMB) << 20,
		MaxUploadBytes: int64(config.FeedbackMaxUploadMB) << 20,
		RetentionDays:  config.FeedbackRetentionDays,
		ClientIP:       clientIP,
		CreateLimiter:  newIPRateLimiter(time.Hour, config.FeedbackTicketsPerHour, config.RateLimitMaxEntries),
		MessageLimiter: newIPRateLimiter(time.Hour, config.FeedbackMessagesPerHour, config.RateLimitMaxEntries),
		UploadLimiter:  newIPRateLimiter(time.Hour, config.FeedbackUploadsPerHour, config.RateLimitMaxEntries),
		ReadLimiter:    newIPRateLimiter(time.Minute, config.FeedbackReadsPerMinute, config.RateLimitMaxEntries),
		OnEvent:        telemetryHub.Notify,
	})
	if err != nil {
		return err
	}
	svc.Register(mux)
	go svc.Run(ctx)
	return nil
}
