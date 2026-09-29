package dev.elu.analytics

/** Controls whether accepted events may create or update a person profile. */
public enum class EluPersonProfilesMode {
    /** Process after identification, person mutations, or participating group activity. */
    IDENTIFIED_ONLY,
    ALWAYS,
    /** Person-changing methods are ignored; event collection and explicit flag context remain available. */
    NEVER,
}
