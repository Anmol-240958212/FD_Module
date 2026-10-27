import { Component, OnDestroy, OnInit } from '@angular/core';
import { Subscription } from 'rxjs';
import { AuthService } from '../../../core/auth.service';
import { ThemeService, ThemeMode } from '../../../core/theme.service';
import { UserRole } from '../../../core/models/auth.models';

export interface NavItem {
  label: string;
  translationKey?: string;
  route: string;
  icon: string;
  roles: UserRole[];
}

@Component({
  selector: 'app-sidebar',
  standalone: false,
  template: `
    <aside class="sidebar">
      <!-- App Brand in Sidebar -->
      <div class="sidebar-brand">
        <div class="brand-logo-box">
          <img src="/logo.png" alt="VaultOne Logo" class="brand-logo-img" />
        </div>
        <div class="brand-info">
          <span class="app-title">VaultOne</span>
          <span class="app-dept">
            {{ currentRole === 'CUSTOMER' ? ('nav.customerPortal' | transloco) : ('nav.adminPortal' | transloco) }}
          </span>
        </div>
      </div>

      <!-- Navigation Menu -->
      <nav class="sidebar-nav">
        <div class="nav-section-title">{{ 'nav.mainNavigation' | transloco }}</div>

        <ul class="nav-list">
          <li *ngFor="let item of visibleNavItems">
            <a
              [routerLink]="item.route"
              routerLinkActive="active"
              [routerLinkActiveOptions]="{ exact: item.route === '/dashboard' || item.route === '/customer/profile' }"
              class="nav-link"
            >
              <app-icon [name]="item.icon" [size]="18" class="nav-icon"></app-icon>
              <span class="nav-label">{{ item.translationKey ? (item.translationKey | transloco) : item.label }}</span>
            </a>
          </li>
        </ul>
      </nav>

      <!-- User Role Indicator & Collapsible Theme Toggle at bottom -->
      <div class="sidebar-footer">
        <a
          routerLink="/profile"
          routerLinkActive="active"
          class="profile-link"
          [routerLinkActiveOptions]="{ exact: true }"
        >
          <app-icon name="user" [size]="16" class="profile-link-icon"></app-icon>
          <span>My Profile</span>
        </a>

        <!-- Collapsible Theme Toggle Button -->
        <div class="theme-collapse-container">
          <button
            type="button"
            class="theme-toggle-header"
            [class.open]="isThemeOpen"
            (click)="toggleThemeMenu()"
            [title]="'nav.toggleTheme' | transloco"
          >
            <div class="theme-header-left">
              <app-icon [name]="getThemeIconName()" [size]="14" class="theme-mode-icon"></app-icon>
              <span class="theme-mode-text">{{ getThemeLabel() }}</span>
            </div>
            <app-icon name="chevron-down" [size]="12" class="theme-chevron" [class.rotated]="isThemeOpen"></app-icon>
          </button>

          <!-- Collapsible Theme Options -->
          <div *ngIf="isThemeOpen" class="theme-options-dropdown animate-fade-in">
            <button
              type="button"
              class="theme-sub-opt"
              [class.active]="currentTheme === 'light'"
              (click)="selectTheme('light')"
            >
              <app-icon name="sun" [size]="14" class="opt-icon"></app-icon>
              <span class="opt-label">{{ 'nav.lightMode' | transloco }}</span>
              <app-icon *ngIf="currentTheme === 'light'" name="check" [size]="12" class="opt-check"></app-icon>
            </button>
            <button
              type="button"
              class="theme-sub-opt"
              [class.active]="currentTheme === 'dark'"
              (click)="selectTheme('dark')"
            >
              <app-icon name="moon" [size]="14" class="opt-icon"></app-icon>
              <span class="opt-label">{{ 'nav.darkMode' | transloco }}</span>
              <app-icon *ngIf="currentTheme === 'dark'" name="check" [size]="12" class="opt-check"></app-icon>
            </button>
            <button
              type="button"
              class="theme-sub-opt"
              [class.active]="currentTheme === 'system'"
              (click)="selectTheme('system')"
            >
              <app-icon name="monitor" [size]="14" class="opt-icon"></app-icon>
              <span class="opt-label">{{ 'nav.auto' | transloco }}</span>
              <app-icon *ngIf="currentTheme === 'system'" name="check" [size]="12" class="opt-check"></app-icon>
            </button>
          </div>
        </div>
      </div>
    </aside>
  `,
  styles: [`
    .sidebar {
      width: var(--sidebar-width);
      background-color: var(--color-bg-surface);
      border-right: 1px solid var(--color-border-subtle);
      display: flex;
      flex-direction: column;
      height: 100vh;
      position: fixed;
      top: 0;
      left: 0;
      z-index: 100;
    }
    .sidebar-brand {
      height: var(--topbar-height);
      display: flex;
      align-items: center;
      gap: var(--space-3);
      padding: 0 var(--space-5);
      border-bottom: 1px solid var(--color-border-subtle);
    }
    .brand-logo-box {
      width: 34px;
      height: 34px;
      border-radius: var(--radius-button);
      background-color: #fff;
      border: 1px solid var(--color-border-default);
      display: flex;
      align-items: center;
      justify-content: center;
      overflow: hidden;
      flex-shrink: 0;
      box-shadow: 0 1px 3px rgba(0,0,0,0.08);
      padding: 2px;
    }
    .brand-logo-img {
      width: 100%;
      height: 100%;
      object-fit: contain;
      display: block;
    }
    .brand-info {
      display: flex;
      flex-direction: column;
    }
    .app-title {
      font-size: var(--text-sm);
      font-weight: 700;
      color: var(--color-text-primary);
      letter-spacing: -0.01em;
      line-height: 1.2;
    }
    .app-dept {
      font-size: var(--text-xs);
      color: var(--color-text-muted);
    }
    .sidebar-nav {
      flex: 1;
      padding: var(--space-4) var(--space-3);
      overflow-y: auto;
    }
    .nav-section-title {
      font-size: 10px;
      font-weight: 600;
      letter-spacing: 0.08em;
      color: var(--color-text-muted);
      padding: var(--space-2) var(--space-3);
      margin-bottom: var(--space-1);
    }
    .nav-list {
      list-style: none;
      display: flex;
      flex-direction: column;
      gap: 2px;
      padding: 0;
      margin: 0;
    }
    .nav-link {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      padding: 10px var(--space-3);
      border-radius: var(--radius-input);
      color: var(--color-text-secondary);
      font-size: var(--text-sm);
      font-weight: 500;
      text-decoration: none;
      transition: background var(--transition-fast), color var(--transition-fast);
      position: relative;
    }
    .nav-link:hover {
      background-color: var(--color-bg-hover);
      color: var(--color-text-primary);
    }
    .nav-link.active {
      background-color: var(--color-bg-elevated);
      color: var(--color-text-primary);
      font-weight: 600;
    }
    .nav-link.active::before {
      content: '';
      position: absolute;
      left: 0;
      top: 6px;
      bottom: 6px;
      width: 3px;
      background-color: var(--color-accent);
      border-radius: 0 2px 2px 0;
    }
    .nav-icon {
      color: var(--color-text-muted);
      flex-shrink: 0;
      display: inline-flex;
      transition: color var(--transition-fast);
    }
    .nav-link:hover .nav-icon {
      color: var(--color-text-primary);
    }
    .nav-link.active .nav-icon {
      color: var(--color-accent);
    }
    .sidebar-footer {
      padding: var(--space-4);
      border-top: 1px solid var(--color-border-subtle);
      background-color: var(--color-bg-surface);
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
    }
    .profile-link {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      width: 100%;
      padding: 8px var(--space-3);
      border: 1px solid var(--color-border-subtle);
      border-radius: var(--radius-input);
      color: var(--color-text-secondary);
      background-color: var(--color-bg-base);
      font-size: var(--text-xs);
      font-weight: 500;
      text-decoration: none;
      transition: background-color var(--transition-fast), color var(--transition-fast), border-color var(--transition-fast);
      position: relative;
    }
    .profile-link:hover,
    .profile-link.active {
      background-color: var(--color-bg-elevated);
      border-color: var(--color-border-focus);
      color: var(--color-text-primary);
    }
    .profile-link.active {
      font-weight: 600;
      box-shadow: inset 3px 0 0 var(--color-accent);
    }
    .profile-link-icon {
      color: var(--color-text-secondary);
      flex-shrink: 0;
    }
    .profile-link.active .profile-link-icon {
      color: var(--color-accent);
    }
    .theme-collapse-container {
      display: flex;
      flex-direction: column;
      position: relative;
    }
    .theme-toggle-header {
      width: 100%;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px var(--space-3);
      background-color: var(--color-bg-base);
      border: 1px solid var(--color-border-subtle);
      border-radius: var(--radius-input);
      color: var(--color-text-primary);
      font-size: var(--text-xs);
      font-weight: 500;
      cursor: pointer;
      transition: all var(--transition-fast);
      user-select: none;
    }
    .theme-toggle-header:hover,
    .theme-toggle-header.open {
      border-color: var(--color-border-focus);
      background-color: var(--color-bg-elevated);
    }
    .theme-header-left {
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .theme-mode-icon {
      color: var(--color-text-secondary);
      flex-shrink: 0;
    }
    .theme-mode-text {
      color: var(--color-text-secondary);
      font-size: 11px;
      letter-spacing: 0.02em;
      font-weight: 500;
    }
    .theme-chevron {
      color: var(--color-text-muted);
      transition: transform var(--transition-fast);
      line-height: 1;
    }
    .theme-chevron.rotated {
      transform: rotate(180deg);
    }
    .theme-options-dropdown {
      margin-bottom: 6px;
      background-color: var(--color-bg-base);
      border: 1px solid var(--color-border-default);
      border-radius: var(--radius-input);
      padding: 4px;
      display: flex;
      flex-direction: column;
      gap: 2px;
      box-shadow: var(--shadow-card);
      order: -1;
    }
    .theme-sub-opt {
      display: flex;
      align-items: center;
      gap: 8px;
      width: 100%;
      padding: 6px 8px;
      background: transparent;
      border: none;
      border-radius: 4px;
      color: var(--color-text-secondary);
      font-size: 11px;
      font-weight: 500;
      cursor: pointer;
      text-align: left;
      transition: background-color var(--transition-fast), color var(--transition-fast);
    }
    .theme-sub-opt:hover {
      background-color: var(--color-bg-hover);
      color: var(--color-text-primary);
    }
    .theme-sub-opt.active {
      background-color: var(--color-bg-elevated);
      color: var(--color-accent);
      font-weight: 600;
    }
    .opt-icon {
      color: var(--color-text-secondary);
      flex-shrink: 0;
    }
    .opt-label {
      flex: 1;
    }
    .opt-check {
      color: var(--color-accent);
      flex-shrink: 0;
    }
  `]
})
export class AppSidebarComponent implements OnInit, OnDestroy {
  currentRole: UserRole | null = null;
  isThemeOpen = false;
  currentTheme: ThemeMode = 'dark';
  private sub?: Subscription;
  private themeSub?: Subscription;

  readonly allNavItems: NavItem[] = [
    {
      label: 'Products & Pricing', translationKey: 'sidebar.products',
      route: '/products',
      icon: 'coins',
      roles: ['STAFF', 'BANK_OFFICER', 'ADMIN']
    },
    {
      label: 'Dashboard', translationKey: 'nav.dashboard',
      route: '/dashboard',
      icon: 'dashboard',
      roles: ['CUSTOMER', 'BANK_OFFICER', 'ADMIN', 'STAFF']
    },
    {
      label: 'My Fixed Deposits', translationKey: 'sidebar.myDeposits',
      route: '/fixed-deposit/accounts',
      icon: 'coins',
      roles: ['CUSTOMER']
    },
    {
      label: 'FD Accounts (All)', translationKey: 'sidebar.allAccounts',
      route: '/fixed-deposit/accounts',
      icon: 'clipboard',
      roles: ['STAFF', 'BANK_OFFICER', 'ADMIN']
    },
    {
      label: 'Create FD Account', translationKey: 'sidebar.createAccount',
      route: '/fixed-deposit/create',
      icon: 'plus',
      roles: ['CUSTOMER', 'STAFF', 'BANK_OFFICER', 'ADMIN']
    },
    {
      label: 'FD Calculator', translationKey: 'nav.calculator',
      route: '/fixed-deposit/calculator',
      icon: 'calculator',
      roles: ['CUSTOMER', 'BANK_OFFICER', 'ADMIN', 'STAFF']
    },
    {
      label: 'Customer KYC & Accounts', translationKey: 'sidebar.customerKyc',
      route: '/customer/list',
      icon: 'users',
      roles: ['STAFF', 'BANK_OFFICER', 'ADMIN']
    },
    {
      label: 'My KYC Profile', translationKey: 'sidebar.myKyc',
      route: '/customer/profile',
      icon: 'user',
      roles: ['CUSTOMER']
    },
    {
      label: 'Batch Operations', translationKey: 'sidebar.batch',
      route: '/admin/batch',
      icon: 'zap',
      roles: ['ADMIN']
    },
    {
      label: 'Employee Management', translationKey: 'sidebar.employees',
      route: '/admin/employees',
      icon: 'shield',
      roles: ['ADMIN']
    },
    {
      label: 'Analytics & Reports', translationKey: 'sidebar.reports',
      route: '/reports',
      icon: 'bar-chart',
      roles: ['CUSTOMER', 'STAFF', 'BANK_OFFICER', 'ADMIN']
    }
  ];

  constructor(
    private readonly authService: AuthService,
    private readonly themeService: ThemeService
  ) {}

  ngOnInit(): void {
    this.sub = this.authService.authState$.subscribe(state => {
      this.currentRole = state.role;
    });

    this.themeSub = this.themeService.themeMode$.subscribe(mode => {
      this.currentTheme = mode;
    });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
    this.themeSub?.unsubscribe();
  }

  toggleThemeMenu(): void {
    this.isThemeOpen = !this.isThemeOpen;
  }

  selectTheme(mode: ThemeMode): void {
    this.themeService.setTheme(mode);
    this.isThemeOpen = false;
  }

  getThemeIconName(): string {
    switch (this.currentTheme) {
      case 'light': return 'sun';
      case 'dark': return 'moon';
      case 'system': return 'monitor';
    }
  }

  getThemeLabel(): string {
    switch (this.currentTheme) {
      case 'light': return 'Light Theme';
      case 'dark': return 'Dark Theme';
      case 'system': return 'System Auto';
    }
  }

  get visibleNavItems(): NavItem[] {
    if (!this.currentRole) {
      return this.allNavItems.filter(item => item.route === '/fixed-deposit/calculator');
    }
    return this.allNavItems.filter(item => item.roles.includes(this.currentRole!));
  }
}

