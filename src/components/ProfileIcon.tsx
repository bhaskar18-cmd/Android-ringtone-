import React from 'react';
import {
  Bell,
  Briefcase,
  Car,
  Home,
  Moon,
  Shield,
  Sun,
  Volume2,
} from 'lucide-react';

interface ProfileIconProps {
  icon: string;
  className?: string;
}

export const ProfileIcon: React.FC<ProfileIconProps> = ({
  icon,
  className = 'w-4 h-4',
}) => {
  switch (icon) {
    case 'moon':
      return <Moon className={className} />;
    case 'briefcase':
      return <Briefcase className={className} />;
    case 'home':
      return <Home className={className} />;
    case 'car':
      return <Car className={className} />;
    case 'bell':
      return <Bell className={className} />;
    case 'volume':
      return <Volume2 className={className} />;
    case 'shield':
      return <Shield className={className} />;
    case 'sun':
    default:
      return <Sun className={className} />;
  }
};
