package ru.mrcrubs.lmsnode.infra;

import ru.mrcrubs.lmsnode.model.DownloadJob;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** PostgreSQL is authoritative; writes fail visibly and never fall back to a file. */
public final class PostgresJobRepository extends InMemoryJobRepository implements AutoCloseable {
    private final Connection connection;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public PostgresJobRepository() {
        try {
            connection = PostgresConnection.open();
            try (PreparedStatement query = connection.prepareStatement("SELECT snapshot::text FROM public.jobs");
                 ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    FileJobRepository.JobSnapshot snapshot = mapper.readValue(rows.getString(1), FileJobRepository.JobSnapshot.class);
                    if (snapshot.jobId() == null || snapshot.type() == null) throw new IllegalStateException("Invalid stored job");
                    super.save(snapshot.toJob());
                }
            }
        } catch (Exception e) { throw new IllegalStateException("PostgreSQL job initialization failed", e); }
    }

    @Override
    public synchronized DownloadJob save(DownloadJob job) {
        try (PreparedStatement query = connection.prepareStatement(
                "INSERT INTO public.jobs(id,snapshot) VALUES(?,?::jsonb) ON CONFLICT(id) DO UPDATE SET snapshot=excluded.snapshot")) {
            query.setString(1, job.getJobId().toString());
            query.setString(2, mapper.writeValueAsString(FileJobRepository.JobSnapshot.from(job)));
            query.executeUpdate();
            return super.save(job);
        } catch (Exception e) { throw new IllegalStateException("PostgreSQL job write failed", e); }
    }

    @Override
    public synchronized void close() throws Exception { connection.close(); }
}
