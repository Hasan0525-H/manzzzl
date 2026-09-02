package design

/**
 * Represents the final approval state of a generated home design.
 */
data class FinalDesignApproval(
    val designId: String,
    val status: ApprovalStatus,
    val approvedVariantId: String?
)

enum class ApprovalStatus {
    DRAFT,
    REVIEWING,
    APPROVED
}
