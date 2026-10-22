package com.personal.tpm.data

/** "The same problem" means the same stage/type, never every later error in the run. */
internal class BatchDecisions {
    private val choices = mutableMapOf<Pair<String, Boolean>, BatchChoice>()
    fun choice(problem: BatchProblem): BatchChoice? = choices[problem.title to problem.canSkipShort]
    fun remember(problem: BatchProblem, choice: BatchChoice) {
        if (choice != BatchChoice.Stop && (choice != BatchChoice.SkipShortages || problem.canSkipShort))
            choices[problem.title to problem.canSkipShort] = choice
    }
}
