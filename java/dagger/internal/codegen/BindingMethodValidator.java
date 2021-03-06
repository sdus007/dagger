/*
 * Copyright (C) 2016 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen;

import static dagger.internal.codegen.langmodel.DaggerElements.isAnyAnnotationPresent;
import static java.util.stream.Collectors.joining;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.element.Modifier.PRIVATE;

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.FormatMethod;
import dagger.internal.codegen.langmodel.DaggerElements;
import dagger.internal.codegen.langmodel.DaggerTypes;
import java.lang.annotation.Annotation;
import java.util.Optional;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/** A validator for methods that represent binding declarations. */
abstract class BindingMethodValidator extends BindingElementValidator<ExecutableElement> {

  private final DaggerElements elements;
  private final DaggerTypes types;
  private final DependencyRequestValidator dependencyRequestValidator;
  private final Class<? extends Annotation> methodAnnotation;
  private final ImmutableSet<? extends Class<? extends Annotation>> enclosingElementAnnotations;
  private final Abstractness abstractness;
  private final ExceptionSuperclass exceptionSuperclass;

  /**
   * Creates a validator object.
   *
   * @param methodAnnotation the annotation on a method that identifies it as a binding method
   * @param enclosingElementAnnotation the method must be declared in a class or interface annotated
   *     with this annotation
   */
  protected BindingMethodValidator(
      DaggerElements elements,
      DaggerTypes types,
      DependencyRequestValidator dependencyRequestValidator,
      Class<? extends Annotation> methodAnnotation,
      Class<? extends Annotation> enclosingElementAnnotation,
      Abstractness abstractness,
      ExceptionSuperclass exceptionSuperclass,
      AllowsMultibindings allowsMultibindings,
      AllowsScoping allowsScoping) {
    this(
        elements,
        types,
        methodAnnotation,
        ImmutableSet.of(enclosingElementAnnotation),
        dependencyRequestValidator,
        abstractness,
        exceptionSuperclass,
        allowsMultibindings,
        allowsScoping);
  }

  /**
   * Creates a validator object.
   *
   * @param methodAnnotation the annotation on a method that identifies it as a binding method
   * @param enclosingElementAnnotations the method must be declared in a class or interface
   *     annotated with one of these annotations
   */
  protected BindingMethodValidator(
      DaggerElements elements,
      DaggerTypes types,
      Class<? extends Annotation> methodAnnotation,
      Iterable<? extends Class<? extends Annotation>> enclosingElementAnnotations,
      DependencyRequestValidator dependencyRequestValidator,
      Abstractness abstractness,
      ExceptionSuperclass exceptionSuperclass,
      AllowsMultibindings allowsMultibindings,
      AllowsScoping allowsScoping) {
    super(methodAnnotation, allowsMultibindings, allowsScoping);
    this.elements = elements;
    this.types = types;
    this.methodAnnotation = methodAnnotation;
    this.enclosingElementAnnotations = ImmutableSet.copyOf(enclosingElementAnnotations);
    this.dependencyRequestValidator = dependencyRequestValidator;
    this.abstractness = abstractness;
    this.exceptionSuperclass = exceptionSuperclass;
  }

  /** The annotation that identifies binding methods validated by this object. */
  final Class<? extends Annotation> methodAnnotation() {
    return methodAnnotation;
  }

  /**
   * Returns an error message of the form "@<i>annotation</i> methods <i>rule</i>", where
   * <i>rule</i> comes from calling {@link String#format(String, Object...)} on {@code ruleFormat}
   * and the other arguments.
   */
  @FormatMethod
  protected final String bindingMethods(String ruleFormat, Object... args) {
    return bindingElements(ruleFormat, args);
  }

  @Override
  protected final String bindingElements() {
    return String.format("@%s methods", methodAnnotation.getSimpleName());
  }

  @Override
  protected final String bindingElementTypeVerb() {
    return "return";
  }

  @Override
  protected final Optional<TypeMirror> bindingElementType(
      ValidationReport.Builder<ExecutableElement> report) {
    return Optional.of(report.getSubject().getReturnType());
  }

  @Override
  protected void checkElement(ValidationReport.Builder<ExecutableElement> builder) {
    super.checkElement(builder);
    checkEnclosingElement(builder);
    checkTypeParameters(builder);
    checkNotPrivate(builder);
    checkAbstractness(builder);
    checkThrows(builder);
    checkParameters(builder);
  }

  /**
   * Adds an error if the method is not declared in a class or interface annotated with one of the
   * {@link #enclosingElementAnnotations}.
   */
  private void checkEnclosingElement(ValidationReport.Builder<ExecutableElement> builder) {
    if (!isAnyAnnotationPresent(
        builder.getSubject().getEnclosingElement(), enclosingElementAnnotations)) {
      builder.addError(
          bindingMethods(
              "can only be present within a @%s",
              enclosingElementAnnotations.stream()
                  .map(Class::getSimpleName)
                  .collect(joining(" or @"))));
    }
  }

  /** Adds an error if the method is generic. */
  private void checkTypeParameters(ValidationReport.Builder<ExecutableElement> builder) {
    if (!builder.getSubject().getTypeParameters().isEmpty()) {
      builder.addError(bindingMethods("may not have type parameters"));
    }
  }

  /** Adds an error if the method is private. */
  private void checkNotPrivate(ValidationReport.Builder<ExecutableElement> builder) {
    if (builder.getSubject().getModifiers().contains(PRIVATE)) {
      builder.addError(bindingMethods("cannot be private"));
    }
  }

  /** Adds an error if the method is abstract but must not be, or is not and must be. */
  private void checkAbstractness(ValidationReport.Builder<ExecutableElement> builder) {
    boolean isAbstract = builder.getSubject().getModifiers().contains(ABSTRACT);
    switch (abstractness) {
      case MUST_BE_ABSTRACT:
        if (!isAbstract) {
          builder.addError(bindingMethods("must be abstract"));
        }
        break;

      case MUST_BE_CONCRETE:
        if (isAbstract) {
          builder.addError(bindingMethods("cannot be abstract"));
        }
    }
  }

  /**
   * Adds an error if the method declares throws anything but an {@link Error} or an appropriate
   * subtype of {@link Exception}.
   */
  private void checkThrows(ValidationReport.Builder<ExecutableElement> builder) {
    exceptionSuperclass.checkThrows(this, builder);
  }

  /** Adds errors for the method parameters. */
  protected void checkParameters(ValidationReport.Builder<ExecutableElement> builder) {
    for (VariableElement parameter : builder.getSubject().getParameters()) {
      checkParameter(builder, parameter);
    }
  }

  /**
   * Adds errors for a method parameter. This implementation reports an error if the parameter has
   * more than one qualifier.
   */
  protected void checkParameter(
      ValidationReport.Builder<ExecutableElement> builder, VariableElement parameter) {
    dependencyRequestValidator.validateDependencyRequest(builder, parameter, parameter.asType());
  }

  /** An abstract/concrete restriction on methods. */
  protected enum Abstractness {
    MUST_BE_ABSTRACT,
    MUST_BE_CONCRETE
  }

  /**
   * The exception class that all {@code throws}-declared throwables must extend, other than {@link
   * Error}.
   */
  protected enum ExceptionSuperclass {
    /** Methods may not declare any throwable types. */
    NO_EXCEPTIONS {
      @Override
      protected String errorMessage(BindingMethodValidator validator) {
        return validator.bindingMethods("may not throw");
      }

      @Override
      protected void checkThrows(
          BindingMethodValidator validator, ValidationReport.Builder<ExecutableElement> builder) {
        if (!builder.getSubject().getThrownTypes().isEmpty()) {
          builder.addError(validator.bindingMethods("may not throw"));
          return;
        }
      }
    },

    /** Methods may throw checked or unchecked exceptions or errors. */
    EXCEPTION(Exception.class) {
      @Override
      protected String errorMessage(BindingMethodValidator validator) {
        return validator.bindingMethods(
            "may only throw unchecked exceptions or exceptions subclassing Exception");
      }
    },

    /** Methods may throw unchecked exceptions or errors. */
    RUNTIME_EXCEPTION(RuntimeException.class) {
      @Override
      protected String errorMessage(BindingMethodValidator validator) {
        return validator.bindingMethods("may only throw unchecked exceptions");
      }
    },
    ;

    private final Class<? extends Exception> superclass;

    ExceptionSuperclass() {
      this(null);
    }

    ExceptionSuperclass(Class<? extends Exception> superclass) {
      this.superclass = superclass;
    }

    /**
     * Adds an error if the method declares throws anything but an {@link Error} or an appropriate
     * subtype of {@link Exception}.
     *
     * <p>This method is overridden in {@link #NO_EXCEPTIONS}.
     */
    protected void checkThrows(
        BindingMethodValidator validator, ValidationReport.Builder<ExecutableElement> builder) {
      TypeMirror exceptionSupertype = validator.elements.getTypeElement(superclass).asType();
      TypeMirror errorType = validator.elements.getTypeElement(Error.class).asType();
      for (TypeMirror thrownType : builder.getSubject().getThrownTypes()) {
        if (!validator.types.isSubtype(thrownType, exceptionSupertype)
            && !validator.types.isSubtype(thrownType, errorType)) {
          builder.addError(errorMessage(validator));
          break;
        }
      }
    }

    protected abstract String errorMessage(BindingMethodValidator validator);
  }
}
