package com.hr;

/** Évalue un CV par rapport à un poste. */
@FunctionalInterface
public interface CvEvaluator {

    EvaluationResult evaluate(CvDocument cv) throws Exception;
}
