package br.com.bonifiq.sdk.fitness

import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * No iOS, uma exceção que a função não declara em `@Throws` encerra o app em vez de chegar ao Swift como erro.
 * Toda função `suspend` pública do [BonifiQFitness] precisa declarar [FitnessException].
 */
class FitnessThrowsDeclarationTest {

    @Test
    fun publicSuspendFunctionsDeclareFitnessException() {
        val suspendFunctions = BonifiQFitness::class.java.declaredMethods.filter { method ->
            Modifier.isPublic(method.modifiers) &&
                !method.isSynthetic &&
                '$' !in method.name &&
                method.parameterTypes.lastOrNull() == Continuation::class.java
        }
        assertTrue(suspendFunctions.size >= 7, "esperava as funções públicas, achou ${suspendFunctions.size}")

        val missing = suspendFunctions
            .filterNot { FitnessException::class.java in it.exceptionTypes }
            .map { it.name }
        assertTrue(missing.isEmpty(), "sem @Throws(FitnessException::class, CancellationException::class): $missing")
    }

    @Test
    fun desktopHasNoHealthPlatform() = kotlinx.coroutines.test.runTest {
        val fitness = BonifiQFitness()
        kotlin.test.assertEquals(HealthPlatform.None, fitness.platform)
        kotlin.test.assertEquals(FitnessAvailability.Unsupported, fitness.availability())
    }
}
