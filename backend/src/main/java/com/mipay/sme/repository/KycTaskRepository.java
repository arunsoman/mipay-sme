package com.mipay.sme.repository;

import com.mipay.sme.domain.kyc.KycTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface KycTaskRepository extends JpaRepository<KycTask, String> {

    List<KycTask> findByApplicationIdAndSubmissionNoOrderByStageSeqAsc(String applicationId, int submissionNo);

    List<KycTask> findByApplicationIdOrderByStageSeqAsc(String applicationId);

    List<KycTask> findByStatusOrderByStageSeqAsc(KycTask.Status status);

    List<KycTask> findByApplicationIdInAndSubmissionNoAndStatus(Collection<String> applicationIds, int submissionNo, KycTask.Status status);

    Optional<KycTask> findByIdAndApplicationId(String id, String applicationId);

    List<KycTask> findByApplicationIdAndSubmissionNo(String applicationId, int submissionNo);

    List<KycTask> findByApplicationIdAndSubmissionNoAndStatus(String applicationId, int submissionNo, KycTask.Status status);

    List<KycTask> findByApplicationIdAndSubmissionNoAndKindAndStatus(String applicationId, int submissionNo, KycTask.Kind kind, KycTask.Status status);
}
