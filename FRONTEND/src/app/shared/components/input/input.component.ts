import { ChangeDetectorRef, Component, forwardRef, Input } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';

@Component({
  selector: 'app-input',
  standalone: false,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => AppInputComponent),
      multi: true
    }
  ],
  template: `
    <div class="form-group" [class.has-error]="!!error">
      <label *ngIf="label" [attr.for]="id" class="form-label">
        {{ label }}
        <span *ngIf="required" class="required-star">*</span>
      </label>

      <div class="input-wrapper" [class.password-input]="type === 'password'" [class.prefixed-input]="prefix">
        <span *ngIf="prefix" class="input-prefix">{{ prefix }}</span>
        <input
          [id]="id"
          [name]="name || id"
          [type]="type === 'password' && showPassword ? 'text' : type"
          [placeholder]="placeholder"
          [disabled]="disabled"
          [readOnly]="readonly"
          [required]="required"
          [min]="min"
          [attr.maxlength]="maxlength || null"
          [value]="value"
          [class.is-invalid]="!!error"
          class="form-input"
          (input)="onInput($event)"
          (blur)="onBlur()"
          (focus)="onFocus()"
        />
        <button
          *ngIf="type === 'password'"
          type="button"
          class="password-toggle"
          [attr.aria-label]="showPassword ? 'Hide password' : 'Show password'"
          [attr.title]="showPassword ? 'Hide password' : 'Show password'"
          (click)="togglePasswordVisibility($event)"
        >
          <app-icon [name]="showPassword ? 'eye-off' : 'eye'" [size]="16"></app-icon>
        </button>
      </div>

      <div *ngIf="error" class="form-error" [id]="id + '-error'" role="alert">
        {{ error }}
      </div>

      <div *ngIf="hint && !error" class="form-hint" [id]="id + '-hint'">
        {{ hint }}
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      width: 100%;
    }
    .required-star {
      color: var(--color-error);
      margin-left: 2px;
    }
    .input-wrapper {
      position: relative;
      width: 100%;
    }
    .password-input .form-input {
      padding-right: 42px;
    }
    .prefixed-input .form-input {
      padding-left: 48px;
    }
    .input-prefix {
      position: absolute;
      left: 14px;
      top: 50%;
      transform: translateY(-50%);
      color: var(--color-text-secondary);
      font-size: inherit;
      pointer-events: none;
      z-index: 1;
    }
    .password-toggle {
      position: absolute;
      top: 50%;
      right: 8px;
      transform: translateY(-50%);
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 28px;
      height: 28px;
      padding: 0;
      border: 0;
      background: transparent;
      color: var(--color-text-muted);
      cursor: pointer;
      border-radius: var(--radius-button);
    }
    .password-toggle:hover,
    .password-toggle:focus-visible {
      color: var(--color-text-primary);
      background: var(--color-bg-hover);
      outline: none;
    }
  `]
})
export class AppInputComponent implements ControlValueAccessor {
  constructor(private readonly cdr: ChangeDetectorRef) {}

  @Input() id = 'input-' + Math.random().toString(36).substring(2, 9);
  @Input() name = '';
  @Input() label = '';
  @Input() type = 'text';
  @Input() placeholder = '';
  @Input() disabled = false;
  @Input() readonly = false;
  @Input() required = false;
  @Input() min: number | string | null = null;
  @Input() maxlength: number | string | null = null;
  @Input() prefix = '';
  @Input() error: string | null = null;
  @Input() hint: string | null = null;

  @Input() value: string | number = '';

  showPassword = false;

  onChange: (val: any) => void = () => {};
  onTouched: () => void = () => {};

  writeValue(value: any): void {
    this.value = value ?? '';
    this.cdr.markForCheck();
  }

  registerOnChange(fn: any): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: any): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    this.cdr.markForCheck();
  }

  onInput(event: Event): void {
    const target = event.target as HTMLInputElement;
    this.value = target.value;
    this.onChange(this.value);
  }

  onBlur(): void {
    this.onTouched();
  }

  onFocus(): void {}

  togglePasswordVisibility(event: Event): void {
    event.preventDefault();
    event.stopPropagation();
    this.showPassword = !this.showPassword;
  }
}
