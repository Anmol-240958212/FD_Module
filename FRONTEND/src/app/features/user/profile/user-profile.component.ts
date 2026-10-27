import { Component, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { UserApiService } from '../../../core/api/user-api.service';
import { AuthService } from '../../../core/auth.service';
import { UserProfileResponse } from '../../../core/models/auth.models';
import { ToastService } from '../../../shared/services/toast.service';

@Component({
  selector: 'app-user-profile',
  standalone: false,
  template: `
    <div class="user-profile-page animate-fade-in">
      <div class="card mb-6">
        <div class="flex-between">
          <div>
            <span class="section-tag">SECURITY & IDENTITY AUDIT</span>
            <h1 class="card-title mt-1">My User Account & Security Settings</h1>
            <p class="card-subtitle">
              Manage personal contact information, verified address, and credential security settings.
            </p>
          </div>
          <app-badge variant="role" [text]="profile?.role || 'CUSTOMER'"></app-badge>
        </div>
      </div>

      <div class="grid-2 mb-6">
        <!-- Profile Overview Card -->
        <app-card title="Account Identity Overview" subtitle="Verified platform identity and KYC status">
          <div *ngIf="loading" class="flex-center py-6">
            <app-spinner size="md" message="Loading profile credentials..."></app-spinner>
          </div>

          <div *ngIf="!loading && profile" class="profile-details-list">
            <div class="detail-row">
              <span class="detail-label">Username:</span>
              <span class="detail-val font-semibold">{{ profile.username }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">System Role:</span>
              <app-badge variant="role" [text]="profile.role"></app-badge>
            </div>
            <div class="detail-row">
              <span class="detail-label">Customer ID:</span>
              <span class="detail-val">{{ profile.customerId ? 'CUST-' + profile.customerId : 'Not Assigned' }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">Full Name:</span>
              <span class="detail-val">{{ getFullName() }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">Registered Email:</span>
              <span class="detail-val">{{ profile.email | mask:'email' }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">Mobile Number:</span>
              <span class="detail-val">{{ profile.phoneNumber ? (profile.phoneNumber | mask:'phone') : 'Not Provided' }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">Identification:</span>
              <span class="detail-val">{{ profile.identificationNumber ? (profile.identificationNumber | mask:'id') : 'Verified Document' }}</span>
            </div>
          </div>
        </app-card>

        <!-- Edit Profile Form Card -->
        <app-card title="Update Contact & Address" subtitle="Modify contact channels and residential address">
          <form [formGroup]="userForm" (ngSubmit)="onSave()" class="user-form">
            <div class="grid-2">
              <app-input
                id="user-phone"
                label="Mobile Phone"
                placeholder="9876543210"
                prefix="+91"
                maxlength="10"
                formControlName="phoneNumber"
              ></app-input>
            </div>

            <div class="grid-2">
              <app-input
                id="user-fname"
                label="First Name"
                formControlName="firstName"
              ></app-input>

              <app-input
                id="user-lname"
                label="Last Name"
                formControlName="lastName"
              ></app-input>
            </div>

            <app-input
              id="user-address1"
              label="Address Line 1"
              placeholder="Apartment/Street details"
              formControlName="addressLine1"
            ></app-input>

            <div class="grid-3">
              <app-input id="user-city" label="City" formControlName="city"></app-input>
              <app-input id="user-state" label="State" formControlName="state"></app-input>
              <app-input id="user-postal" label="Postal Code" formControlName="postalCode"></app-input>
            </div>

            <div class="mt-4">
              <app-button
                type="submit"
                variant="primary"
                [loading]="saving"
                [disabled]="userForm.invalid || saving"
                [block]="true"
              >
                {{ saving ? 'Saving Profile...' : 'Save Profile Changes' }}
              </app-button>
            </div>
          </form>
        </app-card>
      </div>

      <!-- Display & Appearance Preference Card -->
      <app-card title="Interface Appearance" subtitle="Personalize your portal theme and display settings">
        <div class="theme-preference-row">
          <div>
            <span class="theme-pref-title">Theme Mode</span>
            <p class="theme-pref-desc">Choose between Light, Dark, or automatically match your operating system preference.</p>
          </div>
          <app-theme-toggle [compact]="false"></app-theme-toggle>
        </div>
      </app-card>
    </div>
  `,
  styles: [`
    .theme-preference-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-4);
      padding: var(--space-2) 0;
    }
    .theme-pref-title {
      font-size: var(--text-sm);
      font-weight: 600;
      color: var(--color-text-primary);
    }
    .theme-pref-desc {
      font-size: var(--text-xs);
      color: var(--color-text-secondary);
      margin-top: 4px;
    }
    .section-tag {
      font-size: 10px;
      font-weight: 600;
      letter-spacing: 0.08em;
      color: var(--color-text-muted);
    }
    .profile-details-list {
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
    }
    .detail-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-bottom: var(--space-2);
      border-bottom: 1px solid var(--color-border-subtle);
      font-size: var(--text-sm);
    }
    .detail-label {
      color: var(--color-text-secondary);
    }
    .detail-val {
      color: var(--color-text-primary);
    }
    .font-semibold {
      font-weight: 600;
    }
    .user-form {
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
    }
    .flex-center {
      display: flex;
      justify-content: center;
      align-items: center;
    }
    .py-6 {
      padding: var(--space-6) 0;
    }
  `]
})
export class UserProfileComponent implements OnInit {
  profile: UserProfileResponse | null = null;
  loading = false;
  saving = false;
  userForm!: FormGroup;

  constructor(
    private readonly fb: FormBuilder,
    private readonly userApi: UserApiService,
    private readonly authService: AuthService,
    private readonly toastService: ToastService
  ) {}

  ngOnInit(): void {
    this.userForm = this.fb.group({
      phoneNumber: ['', [Validators.pattern(/^[6-9]\d{9}$/)]],
      firstName: [''],
      lastName: [''],
      addressLine1: [''],
      city: [''],
      state: [''],
      postalCode: ['']
    });

    this.loadProfile();

    this.userForm.get('phoneNumber')?.valueChanges.subscribe(value => {
      const digits = String(value || '').replace(/\D/g, '').replace(/^91(?=\d{10}$)/, '');
      if (digits !== value) {
        this.userForm.get('phoneNumber')?.setValue(digits, { emitEvent: false });
      }
    });
  }

  loadProfile(): void {
    this.loading = true;
    this.userApi.getMe().subscribe({
      next: (data) => {
        this.loading = false;
        this.profile = data;
        this.populateForm(data);
      },
      error: () => {
        this.loading = false;
        const currentUsername = this.authService.getUsername() || '';
        const currentRole = this.authService.getRole() || 'CUSTOMER';
        this.profile = {
          username: currentUsername,
          email: currentUsername.includes('@') ? currentUsername : '',
          phoneNumber: '',
          role: currentRole,
          firstName: '',
          lastName: '',
          addressLine1: '',
          city: '',
          state: '',
          postalCode: ''
        };
        this.populateForm(this.profile);
      }
    });
  }

  populateForm(p: UserProfileResponse): void {
    this.userForm.patchValue({
      phoneNumber: this.normalizePhone(p.phoneNumber),
      firstName: p.firstName || '',
      lastName: p.lastName || '',
      addressLine1: p.addressLine1 || '',
      city: p.city || '',
      state: p.state || '',
      postalCode: p.postalCode || ''
    });
  }

  getFullName(): string {
    if (!this.profile) return '—';
    const parts = [this.profile.firstName, this.profile.middleName, this.profile.lastName].filter(Boolean);
    return parts.length > 0 ? parts.join(' ') : this.profile.username;
  }

  private normalizePhone(value?: string): string {
    const digits = String(value || '').replace(/\D/g, '');
    return digits.length === 12 && digits.startsWith('91') ? digits.substring(2) : digits;
  }

  onSave(): void {
    if (this.userForm.invalid || this.saving) return;

    this.saving = true;
    this.userApi.updateMe(this.userForm.value).subscribe({
      next: (updated) => {
        this.saving = false;
        this.profile = updated;
        this.toastService.success('Your profile details have been saved.', 'Profile Updated');
      },
      error: (err) => {
        this.saving = false;
        const message = err?.error?.message || err?.error?.detail || err?.message
          || 'Your profile could not be saved.';
        this.toastService.error(message, 'Profile Update Failed');
      }
    });
  }
}
