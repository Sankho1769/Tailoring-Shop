# PostgreSQL Database Backup & Recovery Procedure

Regular database backups ensure customer measurements and billing records are never lost.

### 1. Create Encrypted/Compressed PostgreSQL Backup
```bash
pg_dump -h localhost -p 5432 -U jolly_app -d jolly_tailor -F c -b -v -f "jolly_tailor_backup_$(date +%Y%m%d).dump"
```

### 2. Restore PostgreSQL Backup
```bash
pg_restore -h localhost -p 5432 -U jolly_app -d jolly_tailor -v -c "jolly_tailor_backup.dump"
```

### 3. Docker Local Backup Command
```bash
docker compose exec -T postgres pg_dump -U jolly_app -d jolly_tailor > backup.sql
```
